package run.halo.ai.suite.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.config.AIProperties.EnhancementConfig;
import run.halo.ai.suite.config.AIProperties.ModelConfig;
import run.halo.ai.suite.config.AIProperties.RetrievalConfig;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * RAG 管线编排 — 增强检索的完整流程
 *
 * Phase 3 管线（每步独立开关，可降级）：
 * 1. Query Rewrite — 用 LLM 改写用户 query（结合对话历史）
 * 2. HyDE — 生成假设性回答，用其 embedding 检索
 * 3. Embed — 将 query（或 HyDE 回答）转为向量
 * 4. Hybrid Retrieve — BM25 + Vector 混合检索 + RRF 融合
 * 5. Rerank — 用 Rerank 模型对候选结果精排
 * 6. Format — 格式化检索结果为上下文文本
 *
 * 每步独立超时（避免单步卡死拖死整条管线，每个超时后 onErrorReturn 降级）：
 *   QUERY_REWRITE_TIMEOUT = 2s
 *   HYDE_TIMEOUT          = 3s（仅 chat 部分，embed 不在内）
 *   CROSS_LANG_TIMEOUT    = 3s（含翻译 + BM25）
 *   RERANK_TIMEOUT        = 2s
 * 整体上限由调用方 ChatService 控制（15s）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RAGPipeline {

    private final HybridRetriever hybridRetriever;
    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    // 子步骤独立超时：单步卡死不拖死全管线，超时即降级
    private static final Duration QUERY_REWRITE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HYDE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CROSS_LANG_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RERANK_TIMEOUT = Duration.ofSeconds(2);

    // 合并去重时跨语言结果的 RRF 排名归一化常数（与 HybridRetriever.RRF_K 一致）
    private static final int MERGE_RRF_K = 60;

    // ===== DebugRAGResult：带追踪信息的返回值 =====

    public record DebugRAGResult(RAGContext context, PipelineTrace trace) {}

    /**
     * 执行 RAG 检索 — 普通模式，不带追踪
     */
    public Mono<RAGContext> retrieve(String query, List<Map<String, String>> history) {
        return doRetrieve(query, history, null);
    }

    /**
     * 执行 RAG 检索 — 调试模式，收集每个阶段的追踪数据
     */
    public Mono<DebugRAGResult> retrieveWithTrace(String query, List<Map<String, String>> history) {
        PipelineTrace trace = new PipelineTrace(query, "NORMAL_CHAT");
        return doRetrieve(query, history, trace)
            .map(ctx -> new DebugRAGResult(ctx, trace));
    }

    /**
     * 核心管线逻辑 — trace == null 时零开销（所有 trace 操作跳过）
     */
    private Mono<RAGContext> doRetrieve(String query, List<Map<String, String>> history,
                                         PipelineTrace trace) {
        return Mono.zip(
            aiProperties.getModelConfig(),
            aiProperties.getRetrievalConfig(),
            aiProperties.getEnhancementConfig()
        ).flatMap(tuple -> {
            ModelConfig modelConfig = tuple.getT1();
            RetrievalConfig retrievalConfig = tuple.getT2();
            EnhancementConfig enhancementConfig = tuple.getT3();
            String searchMode = normalizedSearchMode(retrievalConfig);

            // ── Step 1: Query Rewrite ──
            long rewriteStart = trace != null ? System.currentTimeMillis() : 0;
            Mono<String> queryMono = maybeRewriteQuery(
                query, history, modelConfig, enhancementConfig);

            return queryMono.flatMap(rewrittenQuery -> {
                if (trace != null) {
                    long rewriteEnd = System.currentTimeMillis();
                    if (!rewrittenQuery.equals(query)) {
                        trace.addStage("query_rewrite", "查询改写",
                            rewriteStart, rewriteEnd, "ok", "完成",
                            "'" + query + "' → '" + rewrittenQuery + "'", null);
                    } else {
                        trace.addSkipped("query_rewrite", "查询改写", "未启用或结果未变");
                    }
                }
                log.debug("[RAGPipeline] 实际查询: '{}'", rewrittenQuery);

                if ("keyword".equals(searchMode) || !hasEmbeddingConfig(modelConfig)) {
                    if ("vector".equals(searchMode)) {
                        log.debug("[RAGPipeline] vector 模式需要 Embedding，当前未配置，跳过检索");
                        if (trace != null) {
                            trace.addSkipped("embed", "向量编码", "Embedding 未配置");
                        }
                        return Mono.just(RAGContext.empty());
                    }
                    return retrieveWithoutQueryVector(query, rewrittenQuery, modelConfig,
                        enhancementConfig, retrievalConfig, trace,
                        "keyword".equals(searchMode) ? "keyword 模式" : "Embedding 未配置，hybrid 降级");
                }

                // ── Step 2: HyDE or direct embed ──
                long embedStart = trace != null ? System.currentTimeMillis() : 0;
                Mono<float[]> vectorMono = maybeHydeEmbed(
                    rewrittenQuery, modelConfig, enhancementConfig);

                // ── Step 3 + 4: Embed + Hybrid Retrieve + Cross-Language ──
                return vectorMono.flatMap(queryVector -> {
                    if (trace != null) {
                        Map<String, Object> embedData = new LinkedHashMap<>();
                        embedData.put("dimensions", queryVector.length);
                        trace.addStage("embed", "向量编码", embedStart, System.currentTimeMillis(),
                            "ok", "完成", queryVector.length + " 维向量", embedData);
                    }

                    // 主检索（语义 + 关键词混合）
                    Mono<List<RetrievedDocument>> mainResults =
                        traceWrap(trace, "hybrid_retrieve", "混合检索",
                            () -> hybridRetriever.retrieve(rewrittenQuery, queryVector, retrievalConfig),
                            docs -> docs.size() + " 条结果");

                    // 保留原始查询：也搜一遍原问题，兜底改写偏差
                    Mono<List<RetrievedDocument>> originalResults;
                    if (enhancementConfig.isKeepOriginalQuery()
                        && !rewrittenQuery.equals(query)) {
                        originalResults = traceWrap(trace, "original_query", "原查询兜底",
                            () -> embedQuery(query, modelConfig)
                                .flatMap(origVector ->
                                    hybridRetriever.retrieve(query, origVector, retrievalConfig))
                                .onErrorResume(e -> Mono.just(List.of())),
                            docs -> docs.size() + " 条结果");
                    } else {
                        if (trace != null) {
                            trace.addSkipped("original_query", "原查询兜底", "未启用");
                        }
                        originalResults = Mono.just(List.of());
                    }

                    // 跨语言检索（与主检索并发执行）
                    Mono<List<RetrievedDocument>> crossResults =
                        traceWrap(trace, "cross_language", "跨语言检索",
                            () -> maybeCrossLanguageSearch(rewrittenQuery, modelConfig,
                                enhancementConfig, retrievalConfig),
                            docs -> docs.size() + " 条结果");

                    return Mono.zip(mainResults, originalResults, crossResults)
                        .map(merged -> {
                            long mergeStart = System.currentTimeMillis();
                            // 主检索和原查询兜底已经是 RRF 融合分，无需归一化
                            List<RetrievedDocument> all = new ArrayList<>();
                            all.addAll(merged.getT1());
                            all.addAll(merged.getT2());
                            // 跨语言结果是原始 BM25 分数，需归一化为 RRF 排名分
                            List<RetrievedDocument> crossDocs = merged.getT3();
                            boolean crossNormalized = false;
                            if (!crossDocs.isEmpty()) {
                                int crossMax = enhancementConfig.getCrossLanguageMaxResults();
                                crossDocs.sort(Comparator.comparingDouble(
                                    RetrievedDocument::score).reversed());
                                if (crossMax > 0 && crossDocs.size() > crossMax) {
                                    crossDocs = crossDocs.subList(0, crossMax);
                                }
                                crossDocs = normalizeToRrfRanks(crossDocs, MERGE_RRF_K);
                                crossNormalized = true;
                            }
                            all.addAll(crossDocs);
                            int beforeCount = all.size();
                            List<RetrievedDocument> result = deduplicateAndSort(all, retrievalConfig.getTopN());
                            if (trace != null) {
                                Map<String, Object> mergeData = new LinkedHashMap<>();
                                mergeData.put("before", beforeCount);
                                mergeData.put("after", result.size());
                                if (crossNormalized) {
                                    mergeData.put("crossLanguageNormalized", true);
                                }
                                String mergeDetail = beforeCount + " → " + result.size() + " 条（去重"
                                    + (crossNormalized ? "，跨语言已归一化" : "") + "）";
                                trace.addStage("merge_dedup", "合并去重",
                                    mergeStart, System.currentTimeMillis(),
                                    "ok", "完成", mergeDetail, mergeData);
                            }
                            return result;
                        })
                        .flatMap(docs -> maybeRerank(
                            rewrittenQuery, docs, modelConfig, enhancementConfig, retrievalConfig, trace))
                        .map(docs -> {
                            RAGContext ctx = buildContext(docs, retrievalConfig, enhancementConfig);
                            if (trace != null) {
                                trace.addStage("build_context", "构建上下文",
                                    0, System.currentTimeMillis(), "ok", "完成",
                                    (ctx.contextText() != null ? ctx.contextText().length() : 0) + " 字符上下文",
                                    Map.of("docCount", docs.size(),
                                           "contextLength", ctx.contextText() != null ? ctx.contextText().length() : 0));
                            }
                            return ctx;
                        });
                });
            });
        }).onErrorResume(e -> {
            log.error("[RAGPipeline] 检索失败: {}", e.getMessage());
            return Mono.just(RAGContext.empty());
        });
    }

    private Mono<RAGContext> retrieveWithoutQueryVector(
            String query,
            String rewrittenQuery,
            ModelConfig modelConfig,
            EnhancementConfig enhancementConfig,
            RetrievalConfig retrievalConfig,
            PipelineTrace trace,
            String reason) {
        if (trace != null) {
            trace.addSkipped("embed", "向量编码", reason);
        }

        Mono<List<RetrievedDocument>> mainResults =
            traceWrap(trace, "hybrid_retrieve", "关键词检索",
                () -> hybridRetriever.retrieve(rewrittenQuery, null, retrievalConfig),
                docs -> docs.size() + " 条结果");

        Mono<List<RetrievedDocument>> originalResults;
        if (enhancementConfig.isKeepOriginalQuery() && !rewrittenQuery.equals(query)) {
            originalResults = traceWrap(trace, "original_query", "原查询兜底",
                () -> hybridRetriever.retrieve(query, null, retrievalConfig)
                    .onErrorResume(e -> Mono.just(List.of())),
                docs -> docs.size() + " 条结果");
        } else {
            if (trace != null) {
                trace.addSkipped("original_query", "原查询兜底", "未启用");
            }
            originalResults = Mono.just(List.of());
        }

        Mono<List<RetrievedDocument>> crossResults =
            traceWrap(trace, "cross_language", "跨语言检索",
                () -> maybeCrossLanguageSearch(rewrittenQuery, modelConfig,
                    enhancementConfig, retrievalConfig),
                docs -> docs.size() + " 条结果");

        return Mono.zip(mainResults, originalResults, crossResults)
            .map(merged -> {
                long mergeStart = System.currentTimeMillis();
                List<RetrievedDocument> all = new ArrayList<>();
                all.addAll(merged.getT1());
                all.addAll(merged.getT2());
                List<RetrievedDocument> crossDocs = merged.getT3();
                boolean crossNormalized = false;
                if (!crossDocs.isEmpty()) {
                    int crossMax = enhancementConfig.getCrossLanguageMaxResults();
                    crossDocs.sort(Comparator.comparingDouble(
                        RetrievedDocument::score).reversed());
                    if (crossMax > 0 && crossDocs.size() > crossMax) {
                        crossDocs = crossDocs.subList(0, crossMax);
                    }
                    crossDocs = normalizeToRrfRanks(crossDocs, MERGE_RRF_K);
                    crossNormalized = true;
                }
                all.addAll(crossDocs);
                int beforeCount = all.size();
                List<RetrievedDocument> result = deduplicateAndSort(all, retrievalConfig.getTopN());
                if (trace != null) {
                    Map<String, Object> mergeData = new LinkedHashMap<>();
                    mergeData.put("before", beforeCount);
                    mergeData.put("after", result.size());
                    if (crossNormalized) {
                        mergeData.put("crossLanguageNormalized", true);
                    }
                    String mergeDetail = beforeCount + " → " + result.size() + " 条（去重"
                        + (crossNormalized ? "，跨语言已归一化" : "") + "）";
                    trace.addStage("merge_dedup", "合并去重",
                        mergeStart, System.currentTimeMillis(),
                        "ok", "完成", mergeDetail, mergeData);
                }
                return result;
            })
            .flatMap(docs -> maybeRerank(
                rewrittenQuery, docs, modelConfig, enhancementConfig, retrievalConfig, trace))
            .map(docs -> {
                RAGContext ctx = buildContext(docs, retrievalConfig, enhancementConfig);
                if (trace != null) {
                    trace.addStage("build_context", "构建上下文",
                        0, System.currentTimeMillis(), "ok", "完成",
                        (ctx.contextText() != null ? ctx.contextText().length() : 0) + " 字符上下文",
                        Map.of("docCount", docs.size(),
                            "contextLength", ctx.contextText() != null ? ctx.contextText().length() : 0));
                }
                return ctx;
            });
    }

    private boolean hasEmbeddingConfig(ModelConfig modelConfig) {
        return modelConfig.getEmbeddingApiKey() != null
            && !modelConfig.getEmbeddingApiKey().isBlank();
    }

    private String normalizedSearchMode(RetrievalConfig retrievalConfig) {
        String mode = retrievalConfig.getSearchMode();
        if ("keyword".equals(mode) || "vector".equals(mode) || "hybrid".equals(mode)) {
            return mode;
        }
        return "hybrid";
    }

    // ===== traceWrap：包裹 Mono 以记录阶段追踪 =====

    /**
     * 用 Mono.defer + doOnNext 包裹一个阶段，记录起止时间和结果
     * trace == null 时直接返回原始 Mono，零开销
     */
    private <T> Mono<T> traceWrap(PipelineTrace trace, String name, String label,
                                   Supplier<Mono<T>> work, Function<T, String> detailFn) {
        if (trace == null) return work.get();
        return Mono.defer(() -> {
            long start = System.currentTimeMillis();
            return work.get()
                .doOnNext(result -> {
                    long dur = System.currentTimeMillis() - start;
                    String detail = detailFn.apply(result);
                    Object data = null;
                    // 检索类阶段提取文档摘要
                    if (result instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof RetrievedDocument) {
                        @SuppressWarnings("unchecked")
                        List<RetrievedDocument> docs = (List<RetrievedDocument>) result;
                        data = formatDocData(docs);
                    }
                    trace.addStage(name, label, start, start + dur,
                        "ok", "完成", detail, data);
                })
                .onErrorResume(e -> {
                    long dur = System.currentTimeMillis() - start;
                    trace.addStage(name, label, start, start + dur,
                        "fallback", "降级",
                        e.getClass().getSimpleName() + ": " + e.getMessage(), null);
                    return Mono.error(e);
                });
        });
    }

    /** 提取文档摘要数据（标题+评分+切片内容）用于追踪 */
    private List<Map<String, Object>> formatDocData(List<RetrievedDocument> docs) {
        return docs.stream()
            .limit(20)
            .map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", d.postTitle());
                m.put("score", d.score());
                m.put("chunkIndex", d.chunkIndex());
                m.put("content", d.content());
                return m;
            })
            .collect(Collectors.toList());
    }

    // ===== Step 1: Query Rewrite =====

    private Mono<String> maybeRewriteQuery(String query, List<Map<String, String>> history,
                                            ModelConfig modelConfig, EnhancementConfig enhancementConfig) {
        if (!enhancementConfig.isQueryRewriteToggle() || !modelConfig.isQueryRewriteEnabled()) {
            return Mono.just(query);
        }

        String prompt = enhancementConfig.getQueryRewritePrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = "你是一个查询改写助手。请根据对话历史，将用户的最新问题改写为一个独立、明确、适合搜索引擎检索的查询。只输出改写后的查询，不要解释。";
        }

        if (!enhancementConfig.isQueryRewriteWithHistory() || history == null || history.isEmpty()) {
            return callRewriteLlm(prompt, query, modelConfig);
        }

        return callRewriteLlmWithHistory(prompt, query, history, modelConfig);
    }

    private Mono<String> callRewriteLlm(String prompt, String query, ModelConfig modelConfig) {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", prompt),
            Map.of("role", "user", "content", query)
        );

        String baseUrl = getRewriteBaseUrl(modelConfig);
        String apiKey = getRewriteApiKey(modelConfig);
        String model = modelConfig.getQueryRewriteModel();

        return llmClient.chat(baseUrl, apiKey, model, messages, 0.0f, 256, null, null,
                UsageScenario.SEARCH_QUERY_REWRITE)
            .timeout(QUERY_REWRITE_TIMEOUT)
            .map(result -> {
                String rewritten = result.trim();
                if (rewritten.isEmpty() || rewritten.length() > 500) {
                    return query;
                }
                log.debug("[RAGPipeline] Query Rewrite: '{}' → '{}'", query, rewritten);
                return rewritten;
            })
            .onErrorReturn(query);
    }

    private Mono<String> callRewriteLlmWithHistory(String prompt, String query,
                                                     List<Map<String, String>> history,
                                                     ModelConfig modelConfig) {
        ArrayList<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", prompt));

        int start = Math.max(0, history.size() - 4);
        for (int i = start; i < history.size(); i++) {
            messages.add(history.get(i));
        }
        messages.add(Map.of("role", "user", "content", query));

        String baseUrl = getRewriteBaseUrl(modelConfig);
        String apiKey = getRewriteApiKey(modelConfig);
        String model = modelConfig.getQueryRewriteModel();

        return llmClient.chat(baseUrl, apiKey, model, messages, 0.0f, 256, null, null,
                UsageScenario.SEARCH_QUERY_REWRITE)
            .timeout(QUERY_REWRITE_TIMEOUT)
            .map(result -> {
                String rewritten = result.trim();
                if (rewritten.isEmpty() || rewritten.length() > 500) {
                    return query;
                }
                log.debug("[RAGPipeline] Query Rewrite (with history): '{}' → '{}'", query, rewritten);
                return rewritten;
            })
            .onErrorReturn(query);
    }

    private String getRewriteBaseUrl(ModelConfig modelConfig) {
        String url = modelConfig.getQueryRewriteBaseUrl();
        return (url != null && !url.isBlank()) ? url : modelConfig.getChatBaseUrl();
    }

    private String getRewriteApiKey(ModelConfig modelConfig) {
        String key = modelConfig.getQueryRewriteApiKey();
        return (key != null && !key.isBlank()) ? key : modelConfig.getChatApiKey();
    }

    // ===== Step 2: HyDE =====

    private Mono<float[]> maybeHydeEmbed(String query, ModelConfig modelConfig,
                                          EnhancementConfig enhancementConfig) {
        if (!enhancementConfig.isHydeEnabled()) {
            return embedQuery(query, modelConfig);
        }

        String hydePrompt = enhancementConfig.getHydePrompt();
        if (hydePrompt == null || hydePrompt.isBlank()) {
            hydePrompt = "请针对以下问题写一段可能包含答案的段落。不需要真正正确，只需要写得像一篇相关文档。直接输出段落内容，不要解释。";
        }

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", hydePrompt),
            Map.of("role", "user", "content", query)
        );

        return llmClient.chat(modelConfig.getChatBaseUrl(), modelConfig.getChatApiKey(),
                modelConfig.getChatModel(), messages, 0.7f, 512, null, null,
                UsageScenario.SEARCH_HYDE)
            .timeout(HYDE_TIMEOUT)
            .onErrorResume(e -> {
                log.warn("[RAGPipeline] HyDE chat 失败或超时（{}），降级为直接 embed: {}",
                    HYDE_TIMEOUT, e.getClass().getSimpleName());
                return Mono.just("");
            })
            .flatMap(hydeAnswer -> {
                String answer = hydeAnswer.trim();
                if (answer.isEmpty()) {
                    return embedQuery(query, modelConfig);
                }
                log.debug("[RAGPipeline] HyDE 生成: {} chars", answer.length());
                return embedQuery(answer, modelConfig);
            })
            .onErrorResume(e -> {
                log.warn("[RAGPipeline] HyDE embed 失败，降级为直接 embed: {}", e.getMessage());
                return embedQuery(query, modelConfig);
            });
    }

    // ===== Step 5: Rerank =====

    private Mono<List<RetrievedDocument>> maybeRerank(
            String query, List<RetrievedDocument> docs,
            ModelConfig modelConfig, EnhancementConfig enhancementConfig,
            RetrievalConfig retrievalConfig, PipelineTrace trace) {

        if (!enhancementConfig.isRerankToggle() || !modelConfig.isRerankEnabled()) {
            if (trace != null) {
                trace.addSkipped("rerank", "Rerank 精排", "未启用");
            }
            return Mono.just(docs);
        }

        if (docs.isEmpty()) {
            if (trace != null) {
                trace.addSkipped("rerank", "Rerank 精排", "无候选文档");
            }
            return Mono.just(docs);
        }

        List<String> docTexts = docs.stream()
            .map(doc -> doc.postTitle() + "\n" + doc.content())
            .toList();

        int rerankTopN = enhancementConfig.getRerankTopN();
        long rerankStart = trace != null ? System.currentTimeMillis() : 0;

        return llmClient.rerank(
                modelConfig.getRerankBaseUrl(),
                modelConfig.getRerankApiKey(),
                modelConfig.getRerankModel(),
                query, docTexts, rerankTopN,
                UsageScenario.SEARCH_RERANK
            )
            .timeout(RERANK_TIMEOUT)
            .map(rerankResults -> {
                float threshold = enhancementConfig.getRerankScoreThreshold();
                List<RetrievedDocument> reranked = rerankResults.stream()
                    .filter(rr -> rr.index() < docs.size())
                    .filter(rr -> rr.relevanceScore() >= threshold)
                    .map(rr -> {
                        RetrievedDocument original = docs.get(rr.index());
                        return new RetrievedDocument(
                            original.postId(),
                            original.postTitle(),
                            original.content(),
                            rr.relevanceScore(),
                            original.chunkIndex()
                        );
                    })
                    .toList();

                log.debug("[RAGPipeline] Rerank: {} → {} 条结果 (threshold={})", docs.size(), reranked.size(), threshold);

                if (trace != null) {
                    Map<String, Object> rerankData = new LinkedHashMap<>();
                    rerankData.put("before", docs.size());
                    rerankData.put("after", reranked.size());
                    rerankData.put("threshold", threshold);
                    // 展示所有候选的 rerank 分数（含被过滤的）
                    List<Map<String, Object>> allScored = rerankResults.stream()
                        .filter(rr -> rr.index() < docs.size())
                        .limit(20)
                        .map(rr -> {
                            RetrievedDocument orig = docs.get(rr.index());
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("title", orig.postTitle());
                            m.put("chunkIndex", orig.chunkIndex());
                            m.put("score", rr.relevanceScore());
                            m.put("content", orig.content());
                            m.put("passed", rr.relevanceScore() >= threshold);
                            return m;
                        })
                        .collect(Collectors.toList());
                    rerankData.put("documents", allScored);

                    String status = reranked.isEmpty() && !docs.isEmpty() ? "fallback" : "ok";
                    String statusLabel = reranked.isEmpty() && !docs.isEmpty() ? "全部过滤" : "完成";
                    trace.addStage("rerank", "Rerank 精排", rerankStart, System.currentTimeMillis(),
                        status, statusLabel,
                        docs.size() + " → " + reranked.size() + " 条 (阈值 " + threshold + ")", rerankData);
                }

                return reranked;
            })
            .onErrorResume(e -> {
                log.warn("[RAGPipeline] Rerank 失败，使用原始检索结果: {}", e.getMessage());
                if (trace != null) {
                    trace.addStage("rerank", "Rerank 精排", rerankStart, System.currentTimeMillis(),
                        "fallback", "降级", "Rerank 失败: " + e.getMessage(), null);
                }
                return Mono.just(docs);
            });
    }

    // ===== 跨语言检索 =====

    private Mono<List<RetrievedDocument>> maybeCrossLanguageSearch(
            String query, ModelConfig modelConfig,
            EnhancementConfig enhancementConfig, RetrievalConfig retrievalConfig) {

        if (!enhancementConfig.isCrossLanguageEnabled()) {
            return Mono.just(List.of());
        }

        String targetsStr = enhancementConfig.getCrossLanguageTargets();
        if (targetsStr == null || targetsStr.isBlank()) {
            return Mono.just(List.of());
        }

        String[] targets = targetsStr.split(",");

        return Flux.fromArray(targets)
            .flatMap(target -> {
                String lang = langName(target.trim());
                return translateQuery(query, lang, modelConfig)
                    .flatMap(translated -> {
                        if (translated.equals(query)) {
                            return Mono.just(List.<RetrievedDocument>of());
                        }
                        log.debug("[RAGPipeline] 跨语言检索: '{}' → '{}'", query, translated);
                        return hybridRetriever.searchKeywordOnly(translated, retrievalConfig.getTopK());
                    })
                    .onErrorResume(e -> {
                        log.warn("[RAGPipeline] 跨语言翻译/检索失败: {}", e.getMessage());
                        return Mono.just(List.of());
                    });
            }, 3)
            .collectList()
            .map(results -> results.stream()
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }

    private Mono<String> translateQuery(String query, String targetLang, ModelConfig modelConfig) {
        String prompt = "Translate the following text to " + targetLang
            + ". Only output the translation, nothing else.";
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", prompt),
            Map.of("role", "user", "content", query)
        );

        return llmClient.chat(modelConfig.getChatBaseUrl(), modelConfig.getChatApiKey(),
                modelConfig.getChatModel(), messages, 0.0f, 256, null, null,
                UsageScenario.SEARCH_CROSS_LANGUAGE)
            .timeout(CROSS_LANG_TIMEOUT)
            .map(String::trim)
            .filter(result -> !result.isEmpty() && !result.equals(query))
            .onErrorReturn(query);
    }

    private String langName(String code) {
        return switch (code.trim().toLowerCase()) {
            case "en" -> "English";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "zh" -> "Chinese";
            default -> "English";
        };
    }

    /**
     * 将文档列表的原始分数归一化为 RRF 排名分：1/(k + rank)
     *
     * 用于跨语言检索结果（原始 BM25 分数）与主检索结果（RRF 融合分）合并前的对齐。
     * 输入列表须按原始分降序排列，排名由列表位置决定。
     */
    private List<RetrievedDocument> normalizeToRrfRanks(
            List<RetrievedDocument> docs, int k) {
        if (docs.isEmpty()) return docs;
        List<RetrievedDocument> normalized = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            RetrievedDocument d = docs.get(i);
            normalized.add(new RetrievedDocument(
                d.postId(), d.postTitle(), d.content(),
                1.0f / (k + i + 1),
                d.chunkIndex()));
        }
        return normalized;
    }

    private List<RetrievedDocument> deduplicateAndSort(
            List<RetrievedDocument> docs, int topN) {
        if (docs.isEmpty()) return docs;

        Map<String, RetrievedDocument> merged = new LinkedHashMap<>();
        for (RetrievedDocument doc : docs) {
            String key = doc.postId() + "_" + doc.chunkIndex();
            merged.merge(key, doc,
                (a, b) -> a.score() > b.score() ? a : b);
        }

        return merged.values().stream()
            .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
            .limit(topN)
            .collect(Collectors.toList());
    }

    // ===== 通用方法 =====

    private Mono<float[]> embedQuery(String text, ModelConfig modelConfig) {
        return llmClient.embed(
            modelConfig.getEmbeddingBaseUrl(),
            modelConfig.getEmbeddingApiKey(),
            modelConfig.getEmbeddingModel(),
            text,
            modelConfig.getEmbeddingDimensions(),
            UsageScenario.SEARCH_EMBEDDING
        );
    }

    private RAGContext buildContext(List<RetrievedDocument> docs,
                                    RetrievalConfig retrievalConfig,
                                    EnhancementConfig enhancementConfig) {
        if (docs.isEmpty()) {
            if ("fixed_reply".equals(retrievalConfig.getNoMatchBehavior())) {
                return new RAGContext("", docs, true, retrievalConfig.getNoMatchReply());
            }
            return RAGContext.empty();
        }

        String contextText = docs.stream()
            .map(doc -> {
                int idx = docs.indexOf(doc) + 1;
                return "[%d] %s\n%s".formatted(idx, doc.postTitle(), doc.content());
            })
            .collect(Collectors.joining("\n\n"));

        return new RAGContext(contextText, docs, false, null);
    }

    // ===== RAG Context =====

    public record RAGContext(
        String contextText,
        List<RetrievedDocument> documents,
        boolean noMatch,
        String fixedReply
    ) {
        public static RAGContext empty() {
            return new RAGContext("", List.of(), false, null);
        }
    }
}

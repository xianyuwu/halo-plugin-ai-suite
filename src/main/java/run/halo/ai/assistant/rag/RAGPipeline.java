package run.halo.ai.assistant.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.config.AIProperties.EnhancementConfig;
import run.halo.ai.assistant.config.AIProperties.ModelConfig;
import run.halo.ai.assistant.config.AIProperties.RetrievalConfig;
import run.halo.ai.assistant.llm.LlmClient;

import java.time.Duration;
import java.util.*;
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

    /**
     * 执行 RAG 检索 — 完整增强管线
     *
     * @param query   用户原始问题
     * @param history 对话历史（用于 query rewrite 上下文）
     * @return RAGContext
     */
    public Mono<RAGContext> retrieve(String query, List<Map<String, String>> history) {
        return Mono.zip(
            aiProperties.getModelConfig(),
            aiProperties.getRetrievalConfig(),
            aiProperties.getEnhancementConfig()
        ).flatMap(tuple -> {
            ModelConfig modelConfig = tuple.getT1();
            RetrievalConfig retrievalConfig = tuple.getT2();
            EnhancementConfig enhancementConfig = tuple.getT3();

            // Embedding 未配置 → 跳过 RAG
            if (modelConfig.getEmbeddingApiKey() == null
                || modelConfig.getEmbeddingApiKey().isBlank()) {
                log.debug("[RAGPipeline] Embedding 未配置，跳过检索");
                return Mono.just(RAGContext.empty());
            }

            // ── Step 1: Query Rewrite ──
            Mono<String> queryMono = maybeRewriteQuery(
                query, history, modelConfig, enhancementConfig);

            return queryMono.flatMap(rewrittenQuery -> {
                log.debug("[RAGPipeline] 实际查询: '{}'", rewrittenQuery);

                // ── Step 2: HyDE or direct embed ──
                Mono<float[]> vectorMono = maybeHydeEmbed(
                    rewrittenQuery, modelConfig, enhancementConfig);

                // ── Step 3 + 4: Embed + Hybrid Retrieve + Cross-Language ──
                return vectorMono.flatMap(queryVector -> {
                    // 主检索（语义 + 关键词混合）
                    Mono<List<RetrievedDocument>> mainResults =
                        hybridRetriever.retrieve(rewrittenQuery, queryVector, retrievalConfig);

                    // 跨语言检索（与主检索并发执行）
                    Mono<List<RetrievedDocument>> crossResults =
                        maybeCrossLanguageSearch(rewrittenQuery, modelConfig,
                            enhancementConfig, retrievalConfig);

                    return Mono.zip(mainResults, crossResults)
                        .map(merged -> mergeCrossResults(merged.getT1(), merged.getT2(),
                            retrievalConfig.getTopN()))
                        .flatMap(docs -> maybeRerank(
                            rewrittenQuery, docs, modelConfig, enhancementConfig, retrievalConfig))
                        .map(docs -> buildContext(docs, retrievalConfig, enhancementConfig));
                });
            });
        }).onErrorResume(e -> {
            log.error("[RAGPipeline] 检索失败: {}", e.getMessage());
            return Mono.just(RAGContext.empty());
        });
    }

    // ===== Step 1: Query Rewrite =====

    /**
     * 查询改写 — 用 LLM 把模糊/多轮问题改写为独立、明确的检索 query
     *
     * 例如多轮对话中：
     *   用户之前问了"Docker 安装"，现在问"它怎么配置？"
     *   → 改写为 "Docker 安装后如何配置"
     *
     * 类比：像一个翻译官，把"有上下文才能理解的问题"翻译成"搜索引擎能理解的关键词"
     */
    private Mono<String> maybeRewriteQuery(String query, List<Map<String, String>> history,
                                            ModelConfig modelConfig, EnhancementConfig enhancementConfig) {
        if (!enhancementConfig.isQueryRewriteToggle() || !modelConfig.isQueryRewriteEnabled()) {
            return Mono.just(query);
        }

        // 构建改写 prompt
        String prompt = enhancementConfig.getQueryRewritePrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = "你是一个查询改写助手。请根据对话历史，将用户的最新问题改写为一个独立、明确、适合搜索引擎检索的查询。只输出改写后的查询，不要解释。";
        }

        // 如果不包含历史，直接用 query
        if (!enhancementConfig.isQueryRewriteWithHistory() || history == null || history.isEmpty()) {
            return callRewriteLlm(prompt, query, modelConfig);
        }

        // 包含历史：把历史拼进消息
        return callRewriteLlmWithHistory(prompt, query, history, modelConfig);
    }

    private Mono<String> callRewriteLlm(String prompt, String query, ModelConfig modelConfig) {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", prompt),
            Map.of("role", "user", "content", query)
        );

        // 用改写模型的配置（如果有的话），否则用对话模型
        String baseUrl = getRewriteBaseUrl(modelConfig);
        String apiKey = getRewriteApiKey(modelConfig);
        String model = modelConfig.getQueryRewriteModel();

        return llmClient.chat(baseUrl, apiKey, model, messages, 0.0f, 256)
            .timeout(QUERY_REWRITE_TIMEOUT)
            .map(result -> {
                String rewritten = result.trim();
                if (rewritten.isEmpty() || rewritten.length() > 500) {
                    return query; // 改写结果不合理，用原始 query
                }
                log.debug("[RAGPipeline] Query Rewrite: '{}' → '{}'", query, rewritten);
                return rewritten;
            })
            .onErrorReturn(query); // 改写失败/超时，降级用原始 query
    }

    private Mono<String> callRewriteLlmWithHistory(String prompt, String query,
                                                     List<Map<String, String>> history,
                                                     ModelConfig modelConfig) {
        java.util.ArrayList<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", prompt));

        // 只带最近 4 条历史（2 轮），避免 token 浪费
        int start = Math.max(0, history.size() - 4);
        for (int i = start; i < history.size(); i++) {
            messages.add(history.get(i));
        }
        messages.add(Map.of("role", "user", "content", query));

        String baseUrl = getRewriteBaseUrl(modelConfig);
        String apiKey = getRewriteApiKey(modelConfig);
        String model = modelConfig.getQueryRewriteModel();

        return llmClient.chat(baseUrl, apiKey, model, messages, 0.0f, 256)
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

    /**
     * HyDE (Hypothetical Document Embedding) — 用 LLM 生成假设性回答，用其 embedding 检索
     *
     * 原理：用户的短问题（如"怎么安装"）embedding 和文档 embedding 差异较大。
     * 但如果让 LLM 先写一段"可能的回答"，这段文字的 embedding 会更接近真实文档。
     *
     * 类比：用户问"哪家餐厅好吃"，先让美食博主写一段测评，
     * 然后拿这段测评去和已有点评做匹配，比直接拿"好吃"两个字匹配效果好得多
     */
    private Mono<float[]> maybeHydeEmbed(String query, ModelConfig modelConfig,
                                          EnhancementConfig enhancementConfig) {
        if (!enhancementConfig.isHydeEnabled()) {
            // 直接 embed 原始 query
            return embedQuery(query, modelConfig);
        }

        // 生成假设性回答
        String hydePrompt = enhancementConfig.getHydePrompt();
        if (hydePrompt == null || hydePrompt.isBlank()) {
            hydePrompt = "请针对以下问题写一段可能包含答案的段落。不需要真正正确，只需要写得像一篇相关文档。直接输出段落内容，不要解释。";
        }

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", hydePrompt),
            Map.of("role", "user", "content", query)
        );

        return llmClient.chat(modelConfig.getChatBaseUrl(), modelConfig.getChatApiKey(),
                modelConfig.getChatModel(), messages, 0.7f, 512)
            .timeout(HYDE_TIMEOUT)
            .onErrorResume(e -> {
                // HyDE chat 失败/超时，返回空串走后续降级分支
                log.warn("[RAGPipeline] HyDE chat 失败或超时（{}），降级为直接 embed: {}",
                    HYDE_TIMEOUT, e.getClass().getSimpleName());
                return Mono.just("");
            })
            .flatMap(hydeAnswer -> {
                String answer = hydeAnswer.trim();
                if (answer.isEmpty()) {
                    // HyDE 生成失败/超时，降级为直接 embed query
                    return embedQuery(query, modelConfig);
                }
                log.debug("[RAGPipeline] HyDE 生成: {} chars", answer.length());
                // 用假设性回答的 embedding 替代 query embedding
                return embedQuery(answer, modelConfig);
            })
            .onErrorResume(e -> {
                log.warn("[RAGPipeline] HyDE embed 失败，降级为直接 embed: {}", e.getMessage());
                return embedQuery(query, modelConfig);
            });
    }

    // ===== Step 5: Rerank =====

    /**
     * Rerank 精排 — 用专门的 Rerank 模型对候选结果重新打分排序
     *
     * 混合检索的 RRF 融合是"粗排"，Rerank 模型是"精排"：
     * - 把 query 和每个候选文档一起送入 Rerank 模型
     * - 模型输出更精确的相关性分数（0-1）
     * - 按分数重新排序，过滤低分文档
     *
     * 类比：粗排是"海选"，从 1000 人里挑 20 个；
     * 精排是"面试"，对这 20 人深入评估，挑出最合适的 5 个
     */
    private Mono<List<RetrievedDocument>> maybeRerank(
            String query, List<RetrievedDocument> docs,
            ModelConfig modelConfig, EnhancementConfig enhancementConfig,
            RetrievalConfig retrievalConfig) {

        if (!enhancementConfig.isRerankToggle() || !modelConfig.isRerankEnabled()) {
            return Mono.just(docs);
        }

        if (docs.isEmpty()) {
            return Mono.just(docs);
        }

        // 准备文档文本列表
        List<String> docTexts = docs.stream()
            .map(doc -> doc.postTitle() + "\n" + doc.content())
            .toList();

        int rerankTopN = enhancementConfig.getRerankTopN();

        return llmClient.rerank(
                modelConfig.getRerankBaseUrl(),
                modelConfig.getRerankApiKey(),
                modelConfig.getRerankModel(),
                query, docTexts, rerankTopN
            )
            .timeout(RERANK_TIMEOUT)
            .map(rerankResults -> {
                // Rerank 结果按 index 映射回原始文档，按新分数排序
                List<RetrievedDocument> reranked = rerankResults.stream()
                    .filter(rr -> rr.index() < docs.size())
                    .filter(rr -> rr.relevanceScore() >= enhancementConfig.getRerankScoreThreshold())
                    .map(rr -> {
                        RetrievedDocument original = docs.get(rr.index());
                        return new RetrievedDocument(
                            original.postId(),
                            original.postTitle(),
                            original.content(),
                            rr.relevanceScore(),  // 用 Rerank 分数替代 RRF 分数
                            original.chunkIndex()
                        );
                    })
                    .toList();

                log.debug("[RAGPipeline] Rerank: {} → {} 条结果", docs.size(), reranked.size());
                return reranked;
            })
            .onErrorResume(e -> {
                log.warn("[RAGPipeline] Rerank 失败，使用原始检索结果: {}", e.getMessage());
                return Mono.just(docs);
            });
    }

    // ===== 跨语言检索 =====

    /**
     * 跨语言检索 — 将 query 翻译为目标语言，用翻译后的文本做 BM25 关键词搜索
     *
     * 为什么只做 BM25 不做向量搜索：多语言 Embedding 模型（如 text-embedding-v3）
     * 本身就具备跨语言能力，所以主检索的向量搜索已经覆盖了多语言语义。
     * 这里只补 BM25 关键词命中（中文 query 匹配英文文档的关键词）。
     *
     * 翻译和 BM25 搜索在 Schedulers.boundedElastic() 上执行，不阻塞主线程。
     */
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
                // 翻译 + BM25（并发执行各语言）
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
            }, 3) // concurrency = 3, 最多同时翻译 3 种语言
            .collectList()
            .map(results -> results.stream()
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }

    /**
     * 翻译 query 到目标语言
     */
    private Mono<String> translateQuery(String query, String targetLang, ModelConfig modelConfig) {
        String prompt = "Translate the following text to " + targetLang
            + ". Only output the translation, nothing else.";
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", prompt),
            Map.of("role", "user", "content", query)
        );

        return llmClient.chat(modelConfig.getChatBaseUrl(), modelConfig.getChatApiKey(),
                modelConfig.getChatModel(), messages, 0.0f, 256)
            .timeout(CROSS_LANG_TIMEOUT)
            .map(String::trim)
            .filter(result -> !result.isEmpty() && !result.equals(query))
            .onErrorReturn(query); // 翻译失败/超时用原文
    }

    /**
     * 语言代码 → 语言名称（用于翻译 prompt）
     */
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
     * 合并主检索结果和跨语言结果（按分数排序，去重，取 topN）
     */
    private List<RetrievedDocument> mergeCrossResults(
            List<RetrievedDocument> mainResults,
            List<RetrievedDocument> crossResults,
            int topN) {

        if (crossResults.isEmpty()) return mainResults;
        if (mainResults.isEmpty()) return crossResults.stream()
            .limit(topN).collect(Collectors.toList());

        // 按 id 去重，保留高分版本
        Map<String, RetrievedDocument> merged = new LinkedHashMap<>();
        for (RetrievedDocument doc : mainResults) {
            merged.put(doc.postId() + "_" + doc.chunkIndex(), doc);
        }
        for (RetrievedDocument doc : crossResults) {
            String key = doc.postId() + "_" + doc.chunkIndex();
            // 跨语言结果的分数打 8 折（BM25 分数不可和向量分数直接比）
            RetrievedDocument discounted = new RetrievedDocument(
                doc.postId(), doc.postTitle(), doc.content(),
                doc.score() * 0.8f, doc.chunkIndex()
            );
            merged.merge(key, discounted,
                (a, b) -> a.score() > b.score() ? a : b);
        }

        // 按分数排序取 topN
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
            modelConfig.getEmbeddingDimensions()
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

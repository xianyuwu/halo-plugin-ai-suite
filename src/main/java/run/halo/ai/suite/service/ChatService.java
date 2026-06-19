package run.halo.ai.suite.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.extension.IntentRoute;
import run.halo.ai.suite.intent.PipelineExecutor;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;
import run.halo.ai.suite.rag.PipelineTrace;
import run.halo.ai.suite.rag.RAGPipeline;
import run.halo.ai.suite.rag.RAGPipeline.DebugRAGResult;
import run.halo.ai.suite.rag.RAGPipeline.RAGContext;
import run.halo.ai.suite.rag.RetrievedDocument;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

import java.net.URI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话服务 — 组装消息、调用 LLM、管理上下文
 *
 * Phase 3: RAG 检索增强管线 + 引用来源
 * 流程：用户消息 → RAG 检索（Query Rewrite → HyDE → Retrieve → Rerank）
 *       → 注入 system prompt → 调用 LLM → 返回结果 + 引用来源
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LlmClient llmClient;
    private final AIProperties aiProperties;
    private final RAGPipeline ragPipeline;
    private final ReactiveExtensionClient extensionClient;
    private final ExternalUrlSupplier externalUrlSupplier;
    private final IntentDetector intentDetector;
    private final PipelineExecutor pipelineExecutor;

    // RAG 整体兜底超时：单步已各自超时，这里只是兜全管线异常
    private static final java.time.Duration RAG_OVERALL_TIMEOUT = java.time.Duration.ofSeconds(15);

    /**
     * 将相对 permalink 补全为完整 URL
     * 如果已经是绝对路径（http/https 开头），直接返回
     * 否则用 Halo 配置的 external-url 作为前缀
     */
    private String resolveFullUrl(String permalink) {
        if (permalink == null || permalink.isBlank()) return "";
        if (permalink.startsWith("http://") || permalink.startsWith("https://")) {
            return permalink;
        }
        URI base = externalUrlSupplier.get();
        if (base == null || "/".equals(base.toString())) return permalink;
        return base.toString().replaceAll("/+$", "") + permalink;
    }

    /**
     * 流式对话 — SSE 逐字返回
     *
     * @param userMessage 用户消息
     * @param history     对话历史
     * @return ChatResponse 包含 SSE token 流和引用来源
     */
    public Mono<ChatResponse> chatStreamWithCitations(String userMessage,
                                                       List<Map<String, String>> history) {
        return chatStreamWithCitations(userMessage, history, null);
    }

    /**
     * 搜索页 AI 综合回答 — 复用 RAG 和引用构建，但使用搜索独立的 prompt / maxTokens。
     */
    public Mono<ChatResponse> searchAnswerStreamWithCitations(String keyword) {
        return ragPipeline.retrieve(keyword, List.of())
            .timeout(RAG_OVERALL_TIMEOUT)
            .onErrorResume(e -> {
                log.warn("[ChatService] 搜索 RAG 检索超时或失败，跳过: {}", e.getMessage());
                return Mono.just(RAGPipeline.RAGContext.empty());
            })
            .flatMap(ragContext -> extractCitations(ragContext).map(citations -> {
                if (ragContext.noMatch() && ragContext.fixedReply() != null) {
                    return new ChatResponse(Flux.just(ragContext.fixedReply()), citations);
                }

                Flux<String> stream = aiProperties.getModelConfig()
                    .flatMapMany(modelConfig ->
                        aiProperties.getChatConfig()
                            .zipWith(aiProperties.getSearchConfig())
                            .flatMapMany(tuple -> {
                                var chatConfig = tuple.getT1();
                                var searchConfig = tuple.getT2();
                                String basePrompt = searchConfig.getSystemPrompt() != null
                                    && !searchConfig.getSystemPrompt().isBlank()
                                    ? searchConfig.getSystemPrompt()
                                    : chatConfig.getSystemPrompt();
                                String systemPrompt = buildSystemPrompt(
                                    withSearchBrevityInstruction(basePrompt), ragContext);
                                List<Map<String, String>> messages = buildMessages(
                                    systemPrompt, 0, List.of(), keyword);
                                int maxTokens = searchConfig.getMaxTokens() > 0
                                    ? Math.max(128, Math.min(2048, searchConfig.getMaxTokens()))
                                    : chatConfig.getMaxTokens();
                                return llmClient.chatStream(
                                    modelConfig.getChatBaseUrl(),
                                    modelConfig.getChatApiKey(),
                                    modelConfig.getChatModel(),
                                    messages,
                                    chatConfig.getTemperature(),
                                    maxTokens,
                                    null,
                                    null,
                                    UsageScenario.SEARCH_ANSWER
                                );
                            })
                    );
                return new ChatResponse(stream, citations);
            }));
    }

    /**
     * 流式对话 — 带访客 IP(用于 LLM 调用前的访客限流).
     * clientIp 通过方法参数一路透传到 llmClient.chatStream, 不走 reactor context —
     * 因为 SSE body 的订阅链不在入口 .contextWrite 的下游, context 注入不进去.
     * <p>
     * 意图分流（v5 意图路由框架）：
     * <ol>
     *   <li>{@link IntentDetector} 检测用户问题命中的 IntentRoute</li>
     *   <li>命中 → 走 {@link PipelineExecutor} 跳过 RAG</li>
     *   <li>未命中 → 走默认 RAG 流程</li>
     * </ol>
     */
    public Mono<ChatResponse> chatStreamWithCitations(String userMessage,
                                                       List<Map<String, String>> history,
                                                       String clientIp) {
        return intentDetector.detect(userMessage, history)
            .flatMap(intentOpt -> {
                if (intentOpt.isPresent()) {
                    return chatStreamWithIntent(intentOpt.get(), userMessage, history,
                        clientIp, null);
                }
                return chatStreamWithRag(userMessage, history, clientIp);
            });
    }

    /**
     * 意图路由分支 — 执行 pipeline 拿到 Post 列表，让 LLM 组织回答.
     * <p>对前端完全透明：仍返回 {@link ChatResponse}，citations 由 Post 列表反查 permalink 生成.
     */
    private Mono<ChatResponse> chatStreamWithIntent(IntentRoute route, String userMessage,
                                                     List<Map<String, String>> history,
                                                     String clientIp, PipelineTrace trace) {
        if (trace != null) {
            trace.addStage("intent_route", "意图命中",
                System.currentTimeMillis(), System.currentTimeMillis(),
                "ok", "完成",
                "命中意图: " + (route.getMetadata() != null ? route.getMetadata().getName() : "?"),
                null);
        }
        return pipelineExecutor.execute(route, userMessage, history, trace)
            .flatMap(posts -> buildIntentCitations(posts)
                .map(citations -> {
                    String systemPrompt = buildIntentPrompt(route, posts);
                    Flux<String> stream = aiProperties.getModelConfig()
                        .flatMapMany(modelConfig ->
                            aiProperties.getChatConfig()
                                .flatMapMany(chatConfig -> {
                                    List<Map<String, String>> messages = buildMessages(
                                        systemPrompt, 0, List.of(), userMessage);
                                    return llmClient.chatStream(
                                        modelConfig.getChatBaseUrl(),
                                        modelConfig.getChatApiKey(),
                                        modelConfig.getChatModel(),
                                        messages,
                                        chatConfig.getTemperature(),
                                        chatConfig.getMaxTokens(),
                                        null,
                                        clientIp,
                                        UsageScenario.INTENT_PIPELINE
                                    );
                                })
                        );
                    return new ChatResponse(stream, citations);
                }));
    }

    /**
     * 默认 RAG 分支 — 原 {@code chatStreamWithCitations} 主体（意图未命中时走此路径）.
     */
    private Mono<ChatResponse> chatStreamWithRag(String userMessage,
                                                  List<Map<String, String>> history,
                                                  String clientIp) {
        return ragPipeline.retrieve(userMessage, history)
            .timeout(RAG_OVERALL_TIMEOUT)
            .onErrorResume(e -> {
                log.warn("[ChatService] RAG 检索超时或失败，跳过: {}", e.getMessage());
                return Mono.just(RAGPipeline.RAGContext.empty());
            })
            .flatMap(ragContext -> {
                // 先把 citations 的 permalink 反查出来（reactive），再决定 stream
                return extractCitations(ragContext).map(citations -> {
                    if (ragContext.noMatch() && ragContext.fixedReply() != null) {
                        return new ChatResponse(
                            Flux.just(ragContext.fixedReply()),
                            citations
                        );
                    }

                    // 获取配置并构建流
                    // 由于 Flux 需要在 Mono 内创建，这里用 defer
                    Flux<String> stream = aiProperties.getModelConfig()
                        .flatMapMany(modelConfig ->
                            aiProperties.getChatConfig()
                                .flatMapMany(chatConfig -> {
                                    String systemPrompt = buildSystemPrompt(
                                        chatConfig.getSystemPrompt(), ragContext);
                                    List<Map<String, String>> messages = buildMessages(
                                        systemPrompt, chatConfig.getHistoryTurns(),
                                        history, userMessage);
                                    return llmClient.chatStream(
                                        modelConfig.getChatBaseUrl(),
                                        modelConfig.getChatApiKey(),
                                        modelConfig.getChatModel(),
                                        messages,
                                        chatConfig.getTemperature(),
                                        chatConfig.getMaxTokens(),
                                        null,
                                        clientIp,
                                        UsageScenario.VISITOR_QA
                                    );
                                })
                        );

                    return new ChatResponse(stream, citations);
                });
            });
    }

    /**
     * 流式对话 — 简化版（不需要 citations）
     */
    public Flux<String> chatStream(String userMessage, List<Map<String, String>> history) {
        return chatStreamWithCitations(userMessage, history, null)
            .flatMapMany(ChatResponse::stream);
    }

    /**
     * 非流式对话
     */
    public Mono<String> chat(String userMessage, List<Map<String, String>> history) {
        return chat(userMessage, history, null);
    }

    /**
     * 非流式对话 — 带访客 IP(用于访客限流). clientIp 透传到 llmClient.chat.
     */
    public Mono<String> chat(String userMessage, List<Map<String, String>> history,
                             String clientIp) {
        return intentDetector.detect(userMessage, history)
            .flatMap(intentOpt -> intentOpt.isPresent()
                ? chatWithIntent(intentOpt.get(), userMessage, history, clientIp)
                : chatWithRag(userMessage, history, clientIp));
    }

    private Mono<String> chatWithIntent(IntentRoute route, String userMessage,
                                        List<Map<String, String>> history, String clientIp) {
        return pipelineExecutor.execute(route, userMessage, history, null)
            .flatMap(posts -> aiProperties.getModelConfig()
                .flatMap(modelConfig -> aiProperties.getChatConfig()
                    .flatMap(chatConfig -> llmClient.chat(
                        modelConfig.getChatBaseUrl(),
                        modelConfig.getChatApiKey(),
                        modelConfig.getChatModel(),
                        buildMessages(buildIntentPrompt(route, posts), 0, List.of(), userMessage),
                        chatConfig.getTemperature(),
                        chatConfig.getMaxTokens(),
                        null,
                        clientIp,
                        UsageScenario.INTENT_PIPELINE
                    ))));
    }

    private Mono<String> chatWithRag(String userMessage, List<Map<String, String>> history,
                                     String clientIp) {
        return ragPipeline.retrieve(userMessage, history)
            .timeout(RAG_OVERALL_TIMEOUT)
            .onErrorResume(e -> {
                log.warn("[ChatService] RAG 检索超时或失败，跳过: {}", e.getMessage());
                return Mono.just(RAGPipeline.RAGContext.empty());
            })
            .flatMap(ragContext -> {
                if (ragContext.noMatch() && ragContext.fixedReply() != null) {
                    return Mono.just(ragContext.fixedReply());
                }

                return aiProperties.getModelConfig()
                    .flatMap(modelConfig ->
                        aiProperties.getChatConfig()
                            .flatMap(chatConfig -> {
                                String systemPrompt = buildSystemPrompt(
                                    chatConfig.getSystemPrompt(), ragContext);
                                List<Map<String, String>> messages = buildMessages(
                                    systemPrompt, chatConfig.getHistoryTurns(),
                                    history, userMessage);
                                return llmClient.chat(
                                    modelConfig.getChatBaseUrl(),
                                    modelConfig.getChatApiKey(),
                                    modelConfig.getChatModel(),
                                    messages,
                                    chatConfig.getTemperature(),
                                    chatConfig.getMaxTokens(),
                                    null,
                                    clientIp,
                                    UsageScenario.VISITOR_QA
                                );
                            })
                    );
            });
    }

    /**
     * 使用调用方已经检索好的 RAG context 生成非流式回答。
     * 评测场景用它避免“生成答案”和“取 trace”各跑一次 RAG。
     */
    public Mono<String> chatWithRagContext(String userMessage,
                                           List<Map<String, String>> history,
                                           RAGContext ragContext,
                                           String scenario) {
        RAGContext context = ragContext != null ? ragContext : RAGPipeline.RAGContext.empty();
        if (context.noMatch() && context.fixedReply() != null) {
            return Mono.just(context.fixedReply());
        }

        return aiProperties.getModelConfig()
            .flatMap(modelConfig ->
                aiProperties.getChatConfig()
                    .flatMap(chatConfig -> {
                        String systemPrompt = buildSystemPrompt(
                            chatConfig.getSystemPrompt(), context);
                        List<Map<String, String>> messages = buildMessages(
                            systemPrompt, chatConfig.getHistoryTurns(),
                            history, userMessage);
                        return llmClient.chat(
                            modelConfig.getChatBaseUrl(),
                            modelConfig.getChatApiKey(),
                            modelConfig.getChatModel(),
                            messages,
                            chatConfig.getTemperature(),
                            chatConfig.getMaxTokens(),
                            null,
                            null,
                            scenario != null ? scenario : UsageScenario.UNKNOWN
                        );
                    })
            );
    }

    /**
     * 从检索结果中提取引用来源 — 按 postId 去重保序，并反查 Post.status.permalink 拿真实 URL
     *
     * 为什么实时反查 permalink 而不存进索引：
     * 1. permalink 是相对路径（/archives/{slug}），换域名不影响
     * 2. slug 可被用户修改 → 实时查能跟上变化，索引存的话需要重建
     * 3. PostService 是同 JVM 内存级访问，N 次反查 < 5ms 可忽略
     *
     * 若反查不到（Post 已删），url 字段为空字符串，前端 fallback 为不可点击的标题
     */
    private Mono<List<Map<String, String>>> extractCitations(RAGContext ragContext) {
        if (ragContext.documents() == null || ragContext.documents().isEmpty()) {
            return Mono.just(List.of());
        }

        // 按 postId 去重保序：同一文章不同 chunk 可能都命中，UI 上只展示一次
        Set<String> seen = new HashSet<>();
        List<RetrievedDocument> uniqueDocs = ragContext.documents().stream()
            .filter(doc -> seen.add(doc.postId() != null ? doc.postId() : ""))
            .toList();

        // 并发反查 permalink，concatMap 保序（与 rerank 相关性排名一致）
        return Flux.fromIterable(uniqueDocs)
            .concatMap(this::buildCitation)
            .collectList();
    }

    /** 单条 citation 的构建：反查 permalink，失败/为空时 url 留空串 */
    private Mono<Map<String, String>> buildCitation(RetrievedDocument doc) {
        String title = doc.postTitle() != null ? doc.postTitle() : "";
        String postId = doc.postId() != null ? doc.postId() : "";
        if (postId.isEmpty()) {
            return Mono.just(Map.of("title", title, "postId", "", "url", ""));
        }
        return extensionClient.fetch(Post.class, postId)
            .map(post -> {
                String url = post.getStatus() != null && post.getStatus().getPermalink() != null
                    ? resolveFullUrl(post.getStatus().getPermalink()) : "";
                return Map.of("title", title, "postId", postId, "url", url);
            })
            // Post 已被删除等情况 → 给空 url，前端 fallback 不渲染链接
            .defaultIfEmpty(Map.of("title", title, "postId", postId, "url", ""))
            .onErrorReturn(Map.of("title", title, "postId", postId, "url", ""));
    }

    /**
     * 构建 system prompt — 注入 RAG 上下文，并按 [N] 编号供 LLM inline 引用
     *
     * 编号规则与 extractCitations 完全一致（按 postId 去重保序），确保 LLM 输出的
     * "[1]"、"[2]"、... 能跟前端 citations 数组的第 N 条对应。LLM 不一定 100% 听话，
     * 偶尔漏标 / 乱标可接受 —— 兜底是下方仍展示完整参考文章列表。
     */
    private String buildSystemPrompt(String basePrompt, RAGContext ragContext) {
        if (basePrompt == null) basePrompt = "";

        if (ragContext.documents() == null || ragContext.documents().isEmpty()
            || ragContext.contextText() == null || ragContext.contextText().isBlank()) {
            return basePrompt;
        }

        // 按 postId 分组合并：同一文章的多个 chunk 内容拼在一起，避免去重丢失相关片段
        LinkedHashMap<String, List<RetrievedDocument>> grouped = new LinkedHashMap<>();
        for (RetrievedDocument doc : ragContext.documents()) {
            String pid = doc.postId() != null ? doc.postId() : "";
            grouped.computeIfAbsent(pid, k -> new ArrayList<>()).add(doc);
        }

        // 拼接编号化的检索段落 + 引用指令
        StringBuilder kb = new StringBuilder();
        kb.append("以下是从博客文章中检索到的相关内容，每篇带编号：\n\n");
        int idx = 1;
        for (Map.Entry<String, List<RetrievedDocument>> entry : grouped.entrySet()) {
            List<RetrievedDocument> chunks = entry.getValue();
            String title = chunks.get(0).postTitle() != null ? chunks.get(0).postTitle() : "未命名";
            // 合并同一文章的所有 chunk 内容
            String mergedContent = chunks.stream()
                .map(doc -> doc.content() != null ? doc.content() : "")
                .filter(c -> !c.isBlank())
                .collect(Collectors.joining("\n"));
            kb.append("[").append(idx++).append("] ").append(title).append("\n");
            kb.append(mergedContent).append("\n\n");
        }
        kb.append("引用规则：\n");
        kb.append("1. 回答时如果使用了上面某篇文章的内容，请在相应句子末尾用 [N] 标注（N 是文章编号，例如 [1]、[2]）。\n");
        kb.append("2. 同一句话引用了多篇就连写，如 [1][2]。\n");
        kb.append("3. 如果某些信息不来自上述文章（用你自己的知识补充的），不要加 [N]。\n");
        kb.append("4. 如果检索内容完全无关，可不引用，直接基于自己的知识回答。");

        String knowledgeBlock = kb.toString();
        if (basePrompt.contains("{knowledge}")) {
            return basePrompt.replace("{knowledge}", knowledgeBlock);
        } else {
            return basePrompt + "\n\n" + knowledgeBlock;
        }
    }

    private String withSearchBrevityInstruction(String basePrompt) {
        String prompt = basePrompt == null ? "" : basePrompt;
        return prompt + "\n\n搜索回答要求：请用 3-5 句话简洁回答，优先给结论，再列出关键依据；避免长篇展开。";
    }

    /**
     * 组装消息列表
     */
    private List<Map<String, String>> buildMessages(String systemPrompt, int historyTurns,
                                                     List<Map<String, String>> history,
                                                     String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }

        if (history != null && !history.isEmpty() && historyTurns > 0) {
            int maxHistoryMessages = historyTurns * 2;
            int startIndex = Math.max(0, history.size() - maxHistoryMessages);
            for (int i = startIndex; i < history.size(); i++) {
                messages.add(history.get(i));
            }
        }

        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    /**
     * 构建意图路由的 system prompt — 把 pipeline 处理后的 Post 列表交给 LLM.
     * <p>
     * 若 IntentRoute 配了 {@code outputTemplate} 用它作为输出指引（首行提示 + 规则），
     * 否则用默认格式（编号 + 标题链接 + 发布日期 + 摘要）.
     * <p>原硬编码的 buildHotArticlesPrompt 已迁移为内置 hot-articles 意图的 outputTemplate.
     */
    private String buildIntentPrompt(IntentRoute route, List<Post> posts) {
        String template = route.getSpec() != null ? route.getSpec().getOutputTemplate() : null;
        if (posts == null || posts.isEmpty()) {
            return "未找到符合用户查询的博客文章。请用一句话告知用户没有匹配结果，"
                + "建议换个问法或浏览文章归档页。不要编造文章。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是符合用户查询的博客文章数据。");
        if (template != null && !template.isBlank()) {
            sb.append("\n\n## 输出要求\n").append(template).append("\n");
        } else {
            sb.append("请用编号列表格式输出，每篇标注标题（Markdown 链接）、"
                + "发布日期（YYYY-MM-DD）、一句话点评（结合标题推测主题）。\n");
        }
        sb.append("\n规则：\n1. 标题用 Markdown 超链接 [标题](链接)，不要单独列出 URL\n");
        sb.append("2. 不要编造列表中不存在的文章\n");
        sb.append("3. 用 Markdown 列表或编号列表排版\n\n");
        sb.append("## 文章数据\n\n");

        for (int i = 0; i < posts.size(); i++) {
            Post p = posts.get(i);
            String title = p.getSpec() != null && p.getSpec().getTitle() != null
                ? p.getSpec().getTitle() : "";
            String url = p.getStatus() != null && p.getStatus().getPermalink() != null
                ? resolveFullUrl(p.getStatus().getPermalink()) : "";
            String publishTime = p.getSpec() != null && p.getSpec().getPublishTime() != null
                ? p.getSpec().getPublishTime().toString().substring(0, 10) : "";
            String excerpt = "";
            if (p.getSpec() != null && p.getSpec().getExcerpt() != null
                && p.getSpec().getExcerpt().getRaw() != null) {
                String raw = p.getSpec().getExcerpt().getRaw();
                excerpt = raw.length() > 80 ? raw.substring(0, 80) + "..." : raw;
            }
            sb.append(i + 1).append(". [").append(title).append("](").append(url).append(")");
            if (!publishTime.isEmpty()) {
                sb.append(" · ").append(publishTime);
            }
            if (!excerpt.isEmpty()) {
                sb.append("\n   ").append(excerpt);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从 pipeline 产出的 Post 列表构建 citations — 反查 permalink.
     * <p>范式对齐 {@link #extractCitations}（按 postId 去重保序），保证前端 citations 渲染一致.
     */
    private Mono<List<Map<String, String>>> buildIntentCitations(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return Mono.just(List.of());
        }
        Set<String> seen = new HashSet<>();
        return reactor.core.publisher.Flux.fromIterable(posts)
            .filter(post -> seen.add(post.getMetadata() != null
                ? post.getMetadata().getName() : ""))
            .concatMap(post -> {
                String title = post.getSpec() != null && post.getSpec().getTitle() != null
                    ? post.getSpec().getTitle() : "";
                String postId = post.getMetadata() != null ? post.getMetadata().getName() : "";
                if (postId.isEmpty()) {
                    return Mono.just(Map.of("title", title, "postId", "", "url", ""));
                }
                String url = post.getStatus() != null
                    && post.getStatus().getPermalink() != null
                    ? resolveFullUrl(post.getStatus().getPermalink()) : "";
                return Mono.just(Map.<String, String>of("title", title, "postId", postId, "url", url));
            })
            .collectList();
    }

    /**
     * 对话响应 — 包含 token 流和引用来源
     */
    public record ChatResponse(Flux<String> stream, List<Map<String, String>> citations) {}

    /**
     * 调试对话响应 — 在 ChatResponse 基础上增加管线追踪数据
     */
    public record DebugChatResponse(
        Flux<String> stream,
        List<Map<String, String>> citations,
        PipelineTrace trace
    ) {}

    /**
     * 调试模式流式对话 — 带管线追踪
     *
     * 流程与 chatStreamWithCitations 相同，但用 retrieveWithTrace 收集各阶段追踪数据
     */
    public Mono<DebugChatResponse> chatStreamWithDebug(String userMessage,
                                                         List<Map<String, String>> history,
                                                         String clientIp) {
        long intentStart = System.currentTimeMillis();
        return intentDetector.detect(userMessage, history)
            .flatMap(intentOpt -> {
                String intentName = intentOpt.isPresent()
                    ? (intentOpt.get().getMetadata() != null
                        ? intentOpt.get().getMetadata().getName() : "intent")
                    : "NORMAL_CHAT";
                PipelineTrace trace = new PipelineTrace(userMessage, intentName);
                trace.addStage("chat_intent", "意图识别", intentStart, System.currentTimeMillis(),
                    "ok", "完成", intentName,
                    intentOpt.isPresent()
                        ? Map.of("route", intentName,
                                 "displayName", intentOpt.get().getSpec() != null
                                     ? intentOpt.get().getSpec().getDisplayName() : "")
                        : null);

                if (intentOpt.isPresent()) {
                    // 意图命中：走 pipeline（pipeline 内部每步会 addStage 到 trace）
                    return chatStreamWithIntent(intentOpt.get(), userMessage, history,
                        clientIp, trace)
                        .map(resp -> new DebugChatResponse(resp.stream(), resp.citations(), trace));
                }

                // 未命中：走带追踪的 RAG 流程
                return chatStreamWithRagDebug(userMessage, history, clientIp, trace);
            });
    }

    /**
     * Debug 版 RAG 分支 — 与 {@link #chatStreamWithRag} 同逻辑，但收集各阶段 trace.
     */
    private Mono<DebugChatResponse> chatStreamWithRagDebug(String userMessage,
                                                            List<Map<String, String>> history,
                                                            String clientIp, PipelineTrace trace) {
        return ragPipeline.retrieveWithTrace(userMessage, history)
            .timeout(RAG_OVERALL_TIMEOUT)
            .onErrorResume(e -> {
                log.warn("[ChatService] RAG 检索超时或失败，跳过: {}", e.getMessage());
                PipelineTrace fallbackTrace = new PipelineTrace(userMessage, "NORMAL_CHAT");
                fallbackTrace.addStage("rag_error", "RAG 检索", 0, System.currentTimeMillis(),
                    "error", "异常", e.getMessage(), null);
                return Mono.just(new DebugRAGResult(RAGContext.empty(), fallbackTrace));
            })
            .flatMap(debugResult -> {
                RAGContext ragContext = debugResult.context();
                // 合并 RAG 管线追踪阶段
                trace.stages().addAll(debugResult.trace().stages());

                // 追踪：RAG 结果注入 LLM 的情况
                long injectStart = System.currentTimeMillis();
                int ragDocCount = (ragContext.documents() != null) ? ragContext.documents().size() : 0;
                boolean hasContext = ragDocCount > 0
                    && ragContext.contextText() != null && !ragContext.contextText().isBlank();

                return extractCitations(ragContext).map(citations -> {
                    if (ragContext.noMatch() && ragContext.fixedReply() != null) {
                        trace.addStage("inject_context", "注入上下文", injectStart, System.currentTimeMillis(),
                            "skipped", "跳过", "无匹配，使用固定回复", null);
                        return new DebugChatResponse(
                            Flux.just(ragContext.fixedReply()), citations, trace);
                    }

                    if (!hasContext) {
                        trace.addStage("inject_context", "注入上下文", injectStart, System.currentTimeMillis(),
                            "fallback", "无上下文", "检索到 " + ragDocCount + " 篇但 contextText 为空，LLM 将不使用知识库",
                            Map.of("ragDocCount", ragDocCount));
                    } else {
                        Map<String, Object> injectData = new java.util.LinkedHashMap<>();
                        injectData.put("ragDocCount", ragDocCount);
                        injectData.put("contextLength", ragContext.contextText().length());
                        trace.addStage("inject_context", "注入上下文", injectStart, System.currentTimeMillis(),
                            "ok", "完成", ragDocCount + " 篇文档注入系统提示词，" + ragContext.contextText().length() + " 字符",
                            injectData);
                    }

                    Flux<String> stream = aiProperties.getModelConfig()
                        .flatMapMany(modelConfig ->
                            aiProperties.getChatConfig()
                                .flatMapMany(chatConfig -> {
                                    String systemPrompt = buildSystemPrompt(
                                        chatConfig.getSystemPrompt(), ragContext);
                                    List<Map<String, String>> messages = buildMessages(
                                        systemPrompt, chatConfig.getHistoryTurns(),
                                        history, userMessage);
                                    return llmClient.chatStream(
                                        modelConfig.getChatBaseUrl(),
                                        modelConfig.getChatApiKey(),
                                        modelConfig.getChatModel(),
                                        messages,
                                        chatConfig.getTemperature(),
                                        chatConfig.getMaxTokens(),
                                        null,
                                        clientIp,
                                        UsageScenario.VISITOR_QA
                                    );
                                })
                        );

                    return new DebugChatResponse(stream, citations, trace);
                });
            });
    }
}

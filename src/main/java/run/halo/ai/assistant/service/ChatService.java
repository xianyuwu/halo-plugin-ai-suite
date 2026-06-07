package run.halo.ai.assistant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.llm.LlmClient;
import run.halo.ai.assistant.rag.PipelineTrace;
import run.halo.ai.assistant.rag.RAGPipeline;
import run.halo.ai.assistant.rag.RAGPipeline.DebugRAGResult;
import run.halo.ai.assistant.rag.RAGPipeline.RAGContext;
import run.halo.ai.assistant.rag.RetrievedDocument;
import run.halo.app.core.extension.Counter;
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
     * 流式对话 — 带访客 IP(用于 LLM 调用前的访客限流).
     * clientIp 通过方法参数一路透传到 llmClient.chatStream, 不走 reactor context —
     * 因为 SSE body 的订阅链不在入口 .contextWrite 的下游, context 注入不进去.
     */
    public Mono<ChatResponse> chatStreamWithCitations(String userMessage,
                                                       List<Map<String, String>> history,
                                                       String clientIp) {
        ChatIntent intent = ChatIntent.detect(userMessage);

        // 热门文章意图 → 跳过 RAG，直接查 Counter 浏览量
        if (intent == ChatIntent.HOT_ARTICLES) {
            return fetchHotArticles(10).map(articles -> {
                String hotPrompt = buildHotArticlesPrompt(articles);
                Flux<String> stream = aiProperties.getModelConfig()
                    .flatMapMany(modelConfig ->
                        aiProperties.getChatConfig()
                            .flatMapMany(chatConfig -> {
                                List<Map<String, String>> messages = buildMessages(
                                    hotPrompt, 0, List.of(), userMessage);
                                return llmClient.chatStream(
                                    modelConfig.getChatBaseUrl(),
                                    modelConfig.getChatApiKey(),
                                    modelConfig.getChatModel(),
                                    messages,
                                    chatConfig.getTemperature(),
                                    chatConfig.getMaxTokens(),
                                    null,
                                    clientIp
                                );
                            })
                    );
                // 热门推荐不需要引用来源，返回空 citations
                return new ChatResponse(stream, List.of());
            });
        }

        // 普通对话 → 走 RAG 流程
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
                                        clientIp
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
        return ragPipeline.retrieve(userMessage, history)
            .timeout(RAG_OVERALL_TIMEOUT)
            .onErrorResume(e -> {
                log.warn("[ChatService] RAG 检索超时或失败，跳过: {}", e.getMessage());
                return Mono.just(RAGPipeline.RAGContext.empty());
            })
            .flatMapMany(ragContext -> {
                if (ragContext.noMatch() && ragContext.fixedReply() != null) {
                    return Flux.just(ragContext.fixedReply());
                }

                return aiProperties.getModelConfig()
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
                                    chatConfig.getMaxTokens()
                                );
                            })
                    );
            });
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
                                    clientIp
                                );
                            })
                    );
            });
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
     * 查询热门文章 — 按 Counter 浏览量降序取 top N
     *
     * 流程：list 已发布文章 → 逐个 fetch Counter 拿 visit → 排序 → 截取
     * Counter 是 Halo 内置扩展，name 格式为 posts.content.halo.run/{postName}
     */
    private Mono<List<Map<String, String>>> fetchHotArticles(int limit) {
        return extensionClient.list(Post.class,
                post -> post.isPublished()
                    && !post.isDeleted()
                    && post.getSpec() != null
                    && Boolean.TRUE.equals(post.getSpec().getPublish()),
                null)
            // 逐个查 Counter 拿浏览量，拼成 (post, visits) 对
            .flatMap(post -> {
                String postName = post.getMetadata().getName();
                // Halo Counter 命名格式: posts.content.halo.run/{postName}
                String counterName = "posts.content.halo.run/" + postName;
                return extensionClient.fetch(Counter.class, counterName)
                    .map(counter -> {
                        int visits = counter.getVisit() != null ? counter.getVisit() : 0;
                        return Map.of("post", post, "visits", (Comparable) visits);
                    })
                    // 没有 Counter 记录说明没被访问过，visits = 0
                    .defaultIfEmpty(Map.of("post", post, "visits", (Comparable) 0));
            })
            .collectList()
            .map(entries -> {
                // 按浏览量降序排序
                entries.sort((a, b) -> ((Comparable) b.get("visits")).compareTo(a.get("visits")));
                // 截取 top N，转为前端需要的格式
                return entries.stream()
                    .limit(limit)
                    .map(entry -> {
                        Post post = (Post) entry.get("post");
                        String title = post.getSpec().getTitle();
                        String url = post.getStatus() != null && post.getStatus().getPermalink() != null
                            ? resolveFullUrl(post.getStatus().getPermalink()) : "";
                        int visits = (int) entry.get("visits");
                        return Map.<String, String>of(
                            "title", title != null ? title : "",
                            "url", url,
                            "visits", String.valueOf(visits)
                        );
                    })
                    .toList();
            });
    }

    /**
     * 构建热门文章的 system prompt — 把文章列表交给 LLM 组织回答
     */
    private String buildHotArticlesPrompt(List<Map<String, String>> articles) {
        if (articles.isEmpty()) {
            return "博客目前没有已发布的文章，请告知用户。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是博客中浏览量最高的文章数据，请按指定格式推荐给用户。\n\n");
        sb.append("## 输出格式（严格遵守，不要偏离）\n\n");
        sb.append("先写一句简短的开场白，然后严格按以下模板输出每篇文章：\n\n");
        sb.append("---\n\n");
        sb.append("**1. [文章标题](链接)**\n");
        sb.append("> 浏览量 N · 一句话点评\n\n");
        sb.append("---\n\n");
        sb.append("**2. [文章标题](链接)**\n");
        sb.append("> 浏览量 N · 一句话点评\n\n");
        sb.append("规则：\n");
        sb.append("1. 标题用 Markdown 超链接，不要单独列出 URL\n");
        sb.append("2. 每篇文章之间必须用 --- 分隔\n");
        sb.append("3. 点评要结合标题推测文章主题，10-20 字说清读者能获得什么\n");
        sb.append("4. 严格按浏览量从高到低排列\n");
        sb.append("5. 不要编造不存在的文章\n");
        sb.append("6. 最后一条文章后面不要加 ---\n\n");
        sb.append("## 文章数据\n\n");
        for (int i = 0; i < articles.size(); i++) {
            Map<String, String> a = articles.get(i);
            sb.append(i + 1).append(". [").append(a.get("title")).append("](")
              .append(a.get("url")).append(")")
              .append(" 浏览量: ").append(a.get("visits")).append("\n");
        }
        return sb.toString();
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
        ChatIntent intent = ChatIntent.detect(userMessage);
        PipelineTrace trace = new PipelineTrace(userMessage, intent.name());
        trace.addStage("chat_intent", "意图识别", intentStart, System.currentTimeMillis(),
            "ok", "完成", intent.name(), null);

        // 热门文章意图 → 跳过 RAG
        if (intent == ChatIntent.HOT_ARTICLES) {
            return fetchHotArticles(10).map(articles -> {
                String hotPrompt = buildHotArticlesPrompt(articles);
                Flux<String> stream = aiProperties.getModelConfig()
                    .flatMapMany(modelConfig ->
                        aiProperties.getChatConfig()
                            .flatMapMany(chatConfig -> {
                                List<Map<String, String>> messages = buildMessages(
                                    hotPrompt, 0, List.of(), userMessage);
                                return llmClient.chatStream(
                                    modelConfig.getChatBaseUrl(),
                                    modelConfig.getChatApiKey(),
                                    modelConfig.getChatModel(),
                                    messages,
                                    chatConfig.getTemperature(),
                                    chatConfig.getMaxTokens(),
                                    null,
                                    clientIp
                                );
                            })
                    );
                return new DebugChatResponse(stream, List.of(), trace);
            });
        }

        // 普通对话 → 走带追踪的 RAG 流程
        return ragPipeline.retrieveWithTrace(userMessage, history)
            .timeout(RAG_OVERALL_TIMEOUT)
            .onErrorResume(e -> {
                log.warn("[ChatService] RAG 检索超时或失败，跳过: {}", e.getMessage());
                PipelineTrace fallbackTrace = new PipelineTrace(userMessage, intent.name());
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
                                        clientIp
                                    );
                                })
                        );

                    return new DebugChatResponse(stream, citations, trace);
                });
            });
    }
}

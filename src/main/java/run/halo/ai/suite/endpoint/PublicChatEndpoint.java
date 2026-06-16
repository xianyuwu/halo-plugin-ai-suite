package run.halo.ai.suite.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.endpoint.UsageLimit.LimitExceededException;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import run.halo.ai.suite.service.ChatLogger;
import run.halo.ai.suite.service.ChatLogger.ChatLogEntry;
import run.halo.ai.suite.service.ChatService;
import run.halo.ai.suite.service.ChatService.ChatResponse;
import run.halo.ai.suite.service.ChatService.DebugChatResponse;
import run.halo.ai.suite.service.TraceCache;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 访客聊天 API — 公开接口，无需认证
 *
 * SSE 流格式（Phase 3 增强）：
 * 1. 第一个事件：event:citations  data:[{title, postId}, ...]
 * 2. 后续事件：data:token（逐字输出）
 *
 * 前端 widget 解析 event:citations 事件来显示引用来源
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicChatEndpoint implements CustomEndpoint {

    private final ChatService chatService;
    private final AIProperties aiProperties;
    private final ChatLogger chatLogger;
    private final TraceCache traceCache = new TraceCache();

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_MESSAGE_CHARS = 4000;
    private static final int MAX_HISTORY_ITEMS = 20;
    private static final int MAX_HISTORY_CONTENT_CHARS = 4000;
    private static final int MAX_FEEDBACK_LOG_ID_CHARS = 80;
    private static final int MAX_FEEDBACK_COMMENT_CHARS = 200;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        // 主用 POST（body 传 history，避开 Netty 4KB URL 上限）；GET 路由保留兼容旧前端 / 外部调用，
        // 但中文 history 会很快超 Netty initial line 长度上限触发 TooLongHttpLineException。
        // 匿名访问由 extensions/role-template-public.yaml 精准授权。
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
            .GET("/chat/stream", this::handleStreamChat)
            .POST("/chat/stream", this::handleStreamChatPost)
            .GET("/chat", this::handleChat)
            .POST("/chat", this::handleChatPost)
            .POST("/chat/feedback", this::handleFeedback)
            .GET("/chat/feedback", this::handleFeedback)
            .GET("/widget-config", this::handleWidgetConfig)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.ai-suite.halo.run", "v1alpha1");
    }

    /**
     * 流式对话 — SSE 真流式输出
     *
     * 发送顺序：
     * 1. event: citations → 引用来源（如果有）
     * 2. data: {"content":"token"} → LLM 逐 chunk 推送（JSON 包装，保留前后空格与换行）
     * 3. data: [DONE] → 结束标记
     *
     * 关键：直接桥接 LlmClient.chatStream() 的 Flux<String>，
     * 不再等大模型完整返回后再 split，避免"流式假象"。
     */
    private Mono<ServerResponse> handleStreamChat(ServerRequest request) {
        try {
            ChatRequest chatReq = parseQueryRequest(request);
            return doStreamChat(request, chatReq.message, chatReq.history);
        } catch (IllegalArgumentException e) {
            return validationErrorStream(e.getMessage());
        }
    }

    /** 流式对话核心逻辑 — GET 和 POST 路由共用 */
    private Mono<ServerResponse> doStreamChat(ServerRequest request, String message, List<Map<String, String>> history) {
        // 前置校验: 后台关闭访客聊天(allowGuest=false)时, 拒绝匿名调用, 防止绕过前端隐藏直调 API 触发 LLM 成本
        return ensureGuestChatAllowed().flatMap(allowed -> allowed
            ? doStreamChatInternal(request, message, history)
            : forbiddenStream("访客聊天功能已关闭"));
    }

    private Mono<ServerResponse> doStreamChatInternal(ServerRequest request, String message, List<Map<String, String>> history) {
        final String logId = UUID.randomUUID().toString();
        final StringBuilder answerBuf = new StringBuilder();
        final AtomicBoolean logWritten = new AtomicBoolean(false);
        final String clientIp = extractIp(request);
        final String userAgent = request.headers().firstHeader("User-Agent");

        // clientIp 通过方法参数透传到 ChatService → LlmClient.enforceLimit.
        // 不走 reactor context: SSE body 的订阅链不在入口 .contextWrite 的下游,
        // context 注入不进去, 实测 enforceLimit 永远读到 null (访客限流形同虚设).
        return chatService.chatStreamWithDebug(message, history, clientIp)
            .flatMap(chatResp -> {
                // 缓存 trace 到内存，点踩时取出写入 ChatLog
                traceCache.put(logId, chatResp.trace());

                // 1) citations 首帧（如果有引用来源）
                final List<Map<String, String>> citationsFinal = chatResp.citations() != null
                    ? chatResp.citations() : List.of();
                Flux<ServerSentEvent<String>> citationFrame = Flux.empty();
                if (!citationsFinal.isEmpty()) {
                    String citationJson = toJsonSafe(citationsFinal);
                    citationFrame = Flux.just(
                        ServerSentEvent.<String>builder()
                            .event("citations")
                            .data(citationJson)
                            .build()
                    );
                }

                // 2) 真流式 token 帧: 在流式过程中累积 answerBuf, 流结束异步写日志
                Flux<ServerSentEvent<String>> tokenFrames = chatResp.stream()
                    .filter(token -> token != null && !token.isEmpty())
                    .doOnNext(answerBuf::append)
                    .map(token -> ServerSentEvent.<String>builder()
                        .data(wrapToken(token))
                        .build());

                // 3) logId 帧(在 [DONE] 之前) — 新协议,旧前端 EventSource 忽略未知 event
                Flux<ServerSentEvent<String>> logIdFrame = Flux.just(
                    ServerSentEvent.<String>builder().event("logId").data(logId).build()
                );

                // 4) [DONE] 终止帧
                Flux<ServerSentEvent<String>> doneFrame = Flux.just(
                    ServerSentEvent.<String>builder().data("[DONE]").build()
                );

                Flux<ServerSentEvent<String>> sseStream = citationFrame
                    .concatWith(tokenFrames)
                    .concatWith(logIdFrame)
                    .concatWith(doneFrame)
                    .doFinally(sig -> {
                        // 流结束 (complete/error/cancel) — 异步写日志,只写一次.
                        // doFinally 跑在 reactor event loop 线程上, 整个写日志逻辑必须丢到
                        // boundedElastic 上去, 否则里面的 .block() 会抛
                        // "blocking not supported in thread reactor-http-nio-X" 把 try 块打挂.
                        if (logWritten.compareAndSet(false, true)) {
                            Mono.fromRunnable(() -> writeChatLogSafely(
                                    logId, message, answerBuf, citationsFinal,
                                    clientIp, userAgent))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("[PublicChatEndpoint] 流式输出中断: {}", e.getMessage());
                        return Flux.just(
                            ServerSentEvent.<String>builder()
                                .data(wrapToken(friendlyErrorMessage(e)))
                                .build(),
                            ServerSentEvent.<String>builder().data("[DONE]").build()
                        );
                    });

                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sseStream, ServerSentEvent.class);
            })
            .onErrorResume(e -> {
                log.error("[PublicChatEndpoint] 流式对话失败: {}", e.getMessage());
                Flux<ServerSentEvent<String>> errStream = Flux.just(
                    ServerSentEvent.<String>builder()
                        .data(wrapToken(friendlyErrorMessage(e)))
                        .build(),
                    ServerSentEvent.<String>builder().data("[DONE]").build()
                );
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(errStream, ServerSentEvent.class);
            });
    }

    private Mono<ServerResponse> doStreamChatInternal(ServerRequest request, String message, List<Map<String, String>> history,
                                                      String logId, StringBuilder answerBuf,
                                                      AtomicBoolean logWritten,
                                                      String clientIp, String userAgent) {
        return chatService.chatStreamWithDebug(message, history, clientIp)
            .flatMap(chatResp -> {
                // 缓存 trace 到内存，点踩时取出写入 ChatLog
                traceCache.put(logId, chatResp.trace());

                // 1) citations 首帧（如果有引用来源）
                final List<Map<String, String>> citationsFinal = chatResp.citations() != null
                    ? chatResp.citations() : List.of();
                Flux<ServerSentEvent<String>> citationFrame = Flux.empty();
                if (!citationsFinal.isEmpty()) {
                    String citationJson = toJsonSafe(citationsFinal);
                    citationFrame = Flux.just(
                        ServerSentEvent.<String>builder()
                            .event("citations")
                            .data(citationJson)
                            .build()
                    );
                }

                // 2) 真流式 token 帧: 在流式过程中累积 answerBuf, 流结束异步写日志
                Flux<ServerSentEvent<String>> tokenFrames = chatResp.stream()
                    .filter(token -> token != null && !token.isEmpty())
                    .doOnNext(answerBuf::append)
                    .map(token -> ServerSentEvent.<String>builder()
                        .data(wrapToken(token))
                        .build());

                // 3) logId 帧(在 [DONE] 之前) — 新协议,旧前端 EventSource 忽略未知 event
                Flux<ServerSentEvent<String>> logIdFrame = Flux.just(
                    ServerSentEvent.<String>builder().event("logId").data(logId).build()
                );

                // 4) [DONE] 终止帧
                Flux<ServerSentEvent<String>> doneFrame = Flux.just(
                    ServerSentEvent.<String>builder().data("[DONE]").build()
                );

                Flux<ServerSentEvent<String>> sseStream = citationFrame
                    .concatWith(tokenFrames)
                    .concatWith(logIdFrame)
                    .concatWith(doneFrame)
                    .doFinally(sig -> {
                        // 流结束 (complete/error/cancel) — 异步写日志,只写一次.
                        // doFinally 跑在 reactor event loop 线程上, 整个写日志逻辑必须丢到
                        // boundedElastic 上去, 否则里面的 .block() 会抛
                        // "blocking not supported in thread reactor-http-nio-X" 把 try 块打挂.
                        if (logWritten.compareAndSet(false, true)) {
                            Mono.fromRunnable(() -> writeChatLogSafely(
                                    logId, message, answerBuf, citationsFinal,
                                    clientIp, userAgent))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("[PublicChatEndpoint] 流式输出中断: {}", e.getMessage());
                        return Flux.just(
                            ServerSentEvent.<String>builder()
                                .data(wrapToken(friendlyErrorMessage(e)))
                                .build(),
                            ServerSentEvent.<String>builder().data("[DONE]").build()
                        );
                    });

                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sseStream, ServerSentEvent.class);
            })
            .onErrorResume(e -> {
                log.error("[PublicChatEndpoint] 流式对话失败: {}", e.getMessage());
                Flux<ServerSentEvent<String>> errStream = Flux.just(
                    ServerSentEvent.<String>builder()
                        .data(wrapToken(friendlyErrorMessage(e)))
                        .build(),
                    ServerSentEvent.<String>builder().data("[DONE]").build()
                );
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(errStream, ServerSentEvent.class);
            });
    }

    /**
     * 提取客户端 IP — 优先 X-Forwarded-For(反代), 否则 remoteAddress.
     */
    private String extractIp(ServerRequest request) {
        try {
            String xff = request.headers().firstHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            InetSocketAddress remote = request.remoteAddress().orElse(null);
            if (remote != null && remote.getAddress() != null) {
                return remote.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    /**
     * 反馈提交:GET /chat/feedback?logId=...&type=...&comment=...
     *
     * <p>POST 和 GET 都由插件自己的匿名 RoleTemplate 授权，GET 保留给旧前端兼容。
     */
    private Mono<ServerResponse> handleFeedback(ServerRequest request) {
        String logId = limit(request.queryParam("logId").orElse("").trim(), MAX_FEEDBACK_LOG_ID_CHARS);
        String type = request.queryParam("type").orElse("like");
        String rawComment = request.queryParam("comment").orElse("");
        String comment = limit(rawComment, MAX_FEEDBACK_COMMENT_CHARS);

        if (logId.isEmpty()) {
            return ServerResponse.ok()
                .bodyValue(Map.of("success", false, "error", "logId 必填"));
        }
        if (!"like".equals(type) && !"dislike".equals(type)) {
            return ServerResponse.ok()
                .bodyValue(Map.of("success", false, "error", "type 必须是 like 或 dislike"));
        }
        // 点踩时从内存缓存取出 trace，一并写入 ChatLog
        TraceCache.TraceData traceData = "dislike".equals(type) ? traceCache.take(logId) : null;
        String traceIntent = traceData != null ? traceData.intent() : null;
        List<Map<String, Object>> traceStages = traceData != null ? traceData.stages() : null;

        return chatLogger.updateFeedbackWithTrace(logId, type, comment, traceIntent, traceStages)
            .then(ServerResponse.ok().bodyValue(Map.of("success", true)))
            .onErrorResume(e -> {
                log.error("[Feedback] 更新反馈失败, logId={}", logId, e);
                return ServerResponse.ok()
                    .bodyValue(Map.of("success", false, "error", e.getMessage()));
            });
    }

    /** 把 token 包成 {"content":"..."}，前端按 JSON 分支解析，避免 trim 吃空格 / 换行被拆行 */
    private String wrapToken(String token) {
        try {
            return objectMapper.writeValueAsString(Map.of("content", token));
        } catch (Exception e) {
            // 序列化失败的兜底（理论上不会发生）
            return "{\"content\":\"\"}";
        }
    }

    /**
     * 把后端异常映射成"用户能看懂 + 知道下一步怎么做"的友好文案.
     *
     * <p>使用场景: 流中断 (line 151) / 整个流失败 (line 165) 兜底转成 SSE token
     * 发给前端, 前端通过 onToken 渲染到气泡. 比 "[AI 服务异常，已中断]" 这种技术词
     * 更具体、更可操作.
     */
    static String friendlyErrorMessage(Throwable e) {
        // 1) 限流触发 (LimitGuard 抛) — 区分模型/访客两种来源
        if (e instanceof LimitExceededException) {
            String reason = e.getMessage() != null ? e.getMessage() : "";
            if (reason.contains("本 IP") || reason.contains("本IP") || reason.contains("访客")) {
                return "⚠️ " + reason + " 请稍后再试。";
            }
            return "⚠️ **今日对话已达上限**, 请稍后再来,或联系博主调整限额。";
        }
        // 2) HTTP 4xx/5xx (上游 LLM 服务返回)
        if (e instanceof WebClientResponseException wcre) {
            int code = wcre.getStatusCode().value();
            if (code == 401 || code == 403) {
                return "⚠️ AI 服务授权失败 (HTTP " + code + "),请联系博主检查 API Key 配置。";
            }
            if (code == 429) {
                return "⚠️ AI 服务调用过于频繁,请稍后再试。";
            }
            if (code >= 500) {
                return "⚠️ AI 服务暂时不可用 (HTTP " + code + "),请稍后再试。";
            }
            return "⚠️ 请求出错 (HTTP " + code + "),请稍后重试。";
        }
        // 3) 网络超时 (Spring WebClient 的 Netty 抛)
        if (e.getCause() instanceof java.net.SocketTimeoutException
                || e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
            return "⚠️ 网络连接超时,请检查网络后重试。";
        }
        // 4) 兜底
        return "⚠️ AI 助手暂时不可用,请稍后再试。";
    }

    /**
     * 在 boundedElastic 线程上安全地写一条 chat 日志.
     *
     * <p>从 doFinally 拆出来 — 原内联写法在 reactor event loop 线程上跑,
     * 里面的 {@code .block()} 取 model 值会抛 "blocking not supported".
     * 整个调用必须包到 {@code Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic())}.
     */
    private void writeChatLogSafely(String logId, String message, StringBuilder answerBuf,
                                    List<Map<String, String>> citationsFinal,
                                    String clientIp, String userAgent) {
        try {
            String model = aiProperties.getModelConfig()
                .map(m -> m.getChatModel() != null ? m.getChatModel() : "unknown")
                .defaultIfEmpty("unknown")
                .block();
            ChatLogEntry entry = new ChatLogEntry(
                logId, Instant.now(), clientIp,
                userAgent != null ? userAgent : "",
                message != null ? message : "",
                answerBuf.toString(),
                model != null ? model : "unknown",
                citationsFinal, null, null, null
            );
            chatLogger.appendLogAsync(entry);
        } catch (Exception e) {
            log.warn("[PublicChatEndpoint] 写日志失败: {}", e.getMessage());
        }
    }

    /** 把 citations 列表序列化成 JSON，失败则返回空数组 */
    private String toJsonSafe(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Mono<ServerResponse> handleChat(ServerRequest request) {
        try {
            ChatRequest chatReq = parseQueryRequest(request);
            String clientIp = extractIp(request);
            return doChat(chatReq.message, chatReq.history, clientIp);
        } catch (IllegalArgumentException e) {
            return validationErrorJson(e.getMessage());
        }
    }

    /** 非流式对话核心逻辑 — GET 和 POST 路由共用. clientIp 走方法参数(访客限流用) */
    private Mono<ServerResponse> doChat(String message, List<Map<String, String>> history,
                                        String clientIp) {
        // 前置校验: 后台关闭访客聊天时拒绝, 防止绕过前端隐藏直调 API
        return ensureGuestChatAllowed().flatMap(allowed -> allowed
            ? doChatInternal(message, history, clientIp)
            : forbiddenJson("访客聊天功能已关闭"));
    }

    private Mono<ServerResponse> doChatInternal(String message, List<Map<String, String>> history,
                                                String clientIp) {
        return chatService.chat(message, history, clientIp)
            .onErrorResume(e -> {
                log.error("[PublicChatEndpoint] 对话失败: {}", e.getMessage());
                return Mono.just("抱歉，AI 服务暂时不可用，请稍后重试。");
            })
            .flatMap(reply -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reply", reply))
            );
    }

    /**
     * 访客聊天开关校验 — 读取 chatConfig.allowGuest. 配置读取失败时默认拒绝(安全兜底),
     * 避免配置异常导致功能在不知情下开放.
     *
     * @return true=允许访客聊天; false=后台已关闭, 应拒绝
     */
    private Mono<Boolean> ensureGuestChatAllowed() {
        return aiProperties.getChatConfig()
            .map(chatConfig -> chatConfig != null && chatConfig.isAllowGuest())
            .defaultIfEmpty(false)
            .onErrorResume(e -> {
                log.warn("[PublicChatEndpoint] 读取 chatConfig 失败, 默认拒绝访客聊天: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    /** SSE 路径的拒绝响应 — 发送 error 事件后 [DONE] */
    private Mono<ServerResponse> forbiddenStream(String reason) {
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(Flux.just(
                ServerSentEvent.<String>builder().event("error").data(reason).build(),
                ServerSentEvent.<String>builder().data("[DONE]").build()
            ), String.class);
    }

    /** JSON 路径的拒绝响应 */
    private Mono<ServerResponse> forbiddenJson(String reason) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", reason));
    }

    /**
     * 流式对话 — POST 版本（body 传 history，避开 Netty 4KB URL 上限）
     *
     * Body 格式：{"message":"...", "history":[{"role":"user","content":"..."}, ...]}
     */
    private Mono<ServerResponse> handleStreamChatPost(ServerRequest request) {
        return request.bodyToMono(ChatRequestBody.class)
            .defaultIfEmpty(new ChatRequestBody("", List.of()))
            .flatMap(body -> {
                try {
                    ChatRequest chatReq = normalizeChatRequest(body.message(), body.history());
                    return doStreamChat(request, chatReq.message, chatReq.history);
                } catch (IllegalArgumentException e) {
                    return validationErrorStream(e.getMessage());
                }
            });
    }

    private Mono<ServerResponse> handleChatPost(ServerRequest request) {
        String clientIp = extractIp(request);
        return request.bodyToMono(ChatRequestBody.class)
            .defaultIfEmpty(new ChatRequestBody("", List.of()))
            .flatMap(body -> {
                try {
                    ChatRequest chatReq = normalizeChatRequest(body.message(), body.history());
                    return doChat(chatReq.message, chatReq.history, clientIp);
                } catch (IllegalArgumentException e) {
                    return validationErrorJson(e.getMessage());
                }
            });
    }

    /**
     * 返回浮窗配置 — 供前端 JS 调用
     */
    private Mono<ServerResponse> handleWidgetConfig(ServerRequest request) {
        return Mono.zip(aiProperties.getChatConfig(), aiProperties.getRetrievalConfig(),
                aiProperties.getSearchConfig(), aiProperties.getMindMapConfig())
            .flatMap(tuple -> {
                var chatConfig = tuple.getT1();
                var retrievalConfig = tuple.getT2();
                var searchConfig = tuple.getT3();
                var mindMapConfig = tuple.getT4();
                // 把多行 shortcutQuestions 文本拆成数组，去重去空，限制最多 6 条
                List<String> shortcuts = new ArrayList<>();
                String raw = chatConfig.getShortcutQuestions();
                if (raw != null && !raw.isBlank()) {
                    for (String line : raw.split("\\r?\\n")) {
                        String q = line.trim();
                        if (!q.isEmpty() && !shortcuts.contains(q)) {
                            shortcuts.add(q);
                            if (shortcuts.size() >= 6) break;
                        }
                    }
                }
                Map<String, Object> body = new HashMap<>();
                body.put("position", chatConfig.getWidgetPosition() != null
                    ? chatConfig.getWidgetPosition() : "right-bottom");
                body.put("color", chatConfig.getWidgetThemeColor() != null
                    ? chatConfig.getWidgetThemeColor() : "#5387C4");
                body.put("icon", chatConfig.getWidgetIcon() != null
                    ? chatConfig.getWidgetIcon() : "ri-chat-3-line");
                body.put("triggerSize", chatConfig.getWidgetTriggerSize() > 0
                    ? chatConfig.getWidgetTriggerSize() : 35);
                body.put("triggerLabel", chatConfig.getWidgetTriggerLabel() != null
                    ? chatConfig.getWidgetTriggerLabel() : "AI");
                // 深浅色模式：auto / light / dark；前端写到 data-theme 属性，CSS 据此覆盖 prefers-color-scheme
                String theme = chatConfig.getWidgetTheme();
                if (theme == null || (!theme.equals("light") && !theme.equals("dark"))) {
                    theme = "auto";
                }
                body.put("theme", theme);
                body.put("welcome", chatConfig.getWelcomeMessage() != null
                    ? chatConfig.getWelcomeMessage() : "Hi! 有什么想了解的？");
                body.put("shortcuts", shortcuts);
                body.put("width", chatConfig.getWidgetWidth());
                body.put("height", chatConfig.getWidgetHeight());
                String triggerAlign = chatConfig.getWidgetTriggerAlign();
                // 归一化为 auto / manual：manual 时前端用 triggerOffsetY；auto 时前端固定距底 80px。
                // 历史遗留的 fixed_right/fixed_left（与左右位置选项重复）一律归入 auto。
                body.put("triggerAlign",
                    "manual".equals(triggerAlign) ? "manual" : "auto");
                body.put("triggerOffsetY",
                    chatConfig.getWidgetTriggerOffsetY() > 0 ? chatConfig.getWidgetTriggerOffsetY() : 125);
                body.put("triggerOffsetX",
                    chatConfig.getWidgetTriggerOffsetX() > 0 ? chatConfig.getWidgetTriggerOffsetX() : 17);
                // 形状归一化：只允许 square/rounded/circle，其余一律 fallback 到 square（当前默认）
                String triggerShape = chatConfig.getWidgetTriggerShape();
                body.put("triggerShape",
                    "square".equals(triggerShape) || "rounded".equals(triggerShape)
                    || "circle".equals(triggerShape)
                        ? triggerShape : "square");
                body.put("stream", chatConfig.isStreamOutput());
                body.put("allowGuest", chatConfig.isAllowGuest());
                body.put("showRetrievalStatus", chatConfig.isShowRetrievalStatus());
                body.put("showPrivacyTip", chatConfig.isShowPrivacyTip());
                body.put("showReferences", retrievalConfig.isShowReferences());
                body.put("searchEnabled", searchConfig.isEnabled());
                body.put("searchShowAiAnswer", searchConfig.isShowAiAnswer());
                body.put("searchTheme", normalizeComponentTheme(searchConfig.getTheme()));
                body.put("searchColor", resolveComponentColor(
                    searchConfig.getThemeColor(), chatConfig.getWidgetThemeColor()));
                body.put("mindmapEnabled", mindMapConfig.isEnabled());
                body.put("mindmapTheme", normalizeComponentTheme(mindMapConfig.getTheme()));
                body.put("mindmapColor", resolveComponentColor(
                    mindMapConfig.getThemeColor(), chatConfig.getWidgetThemeColor()));
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
            });
    }

    private static String normalizeComponentTheme(String theme) {
        if ("light".equals(theme) || "dark".equals(theme) || "auto".equals(theme)
            || "system".equals(theme)) {
            return theme;
        }
        return "inherit";
    }

    private static String resolveComponentColor(String componentColor, String chatColor) {
        if (componentColor != null && !componentColor.isBlank()) {
            return componentColor;
        }
        return chatColor != null && !chatColor.isBlank() ? chatColor : "#4F46E5";
    }

    /**
     * 从 GET query 参数解析请求
     * message: 必填，用户消息
     * history: 选填，JSON 数组格式 [{"role":"user","content":"xxx"}, ...]
     */
    private ChatRequest parseQueryRequest(ServerRequest request) {
        String message = request.queryParam("message").orElse("");

        List<Map<String, String>> history = new ArrayList<>();
        String historyJson = request.queryParam("history").orElse("");
        if (!historyJson.isBlank()) {
            try {
                JsonNode historyNode = objectMapper.readTree(historyJson);
                if (historyNode.isArray()) {
                    for (JsonNode item : historyNode) {
                        if (history.size() >= MAX_HISTORY_ITEMS) {
                            break;
                        }
                        Map<String, String> msg = new HashMap<>();
                        msg.put("role", item.path("role").asText("user"));
                        msg.put("content", item.path("content").asText(""));
                        history.add(msg);
                    }
                }
            } catch (Exception e) {
                log.warn("[PublicChatEndpoint] 解析 history 参数失败: {}", e.getMessage());
            }
        }
        return normalizeChatRequest(message, history);
    }

    private ChatRequest normalizeChatRequest(String rawMessage, List<Map<String, String>> rawHistory) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 必填");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("message 超过 " + MAX_MESSAGE_CHARS + " 字符限制");
        }

        List<Map<String, String>> history = new ArrayList<>();
        if (rawHistory != null) {
            for (Map<String, String> item : rawHistory) {
                if (history.size() >= MAX_HISTORY_ITEMS) {
                    break;
                }
                if (item == null) continue;
                String role = item.getOrDefault("role", "user");
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                String content = item.getOrDefault("content", "");
                if (content == null || content.isBlank()) {
                    continue;
                }
                history.add(Map.of("role", role, "content", limit(content, MAX_HISTORY_CONTENT_CHARS)));
            }
        }
        return new ChatRequest(message, history);
    }

    private Mono<ServerResponse> validationErrorJson(String message) {
        return ServerResponse.badRequest()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("error", message));
    }

    private Mono<ServerResponse> validationErrorStream(String message) {
        Flux<ServerSentEvent<String>> errStream = Flux.just(
            ServerSentEvent.<String>builder()
                .data(wrapToken("⚠️ " + message))
                .build(),
            ServerSentEvent.<String>builder().data("[DONE]").build()
        );
        return ServerResponse.badRequest()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(errStream, ServerSentEvent.class);
    }

    private static String limit(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    record ChatRequest(String message, List<Map<String, String>> history) {}

    /** POST body — JSON 反序列化目标 */
    record ChatRequestBody(String message, List<Map<String, String>> history) {}
}

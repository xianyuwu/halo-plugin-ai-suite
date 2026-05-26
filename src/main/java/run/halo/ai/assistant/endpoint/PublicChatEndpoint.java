package run.halo.ai.assistant.endpoint;

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
import run.halo.ai.assistant.config.AIProperties;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.ai.assistant.service.ChatService;
import run.halo.ai.assistant.service.ChatService.ChatResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        // 主用 POST（body 传 history，避开 Netty 4KB URL 上限）；GET 路由保留兼容旧前端 / 外部调用，
        // 但中文 history 会很快超 Netty initial line 长度上限触发 TooLongHttpLineException。
        // 经验证：/apis/api.halo.run/** 的匿名 POST 是通的（404 vs 302），不需要认证。
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
            .GET("/chat/stream", this::handleStreamChat)
            .POST("/chat/stream", this::handleStreamChatPost)
            .GET("/chat", this::handleChat)
            .POST("/chat", this::handleChatPost)
            .GET("/widget-config", this::handleWidgetConfig)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        // 使用 api.halo.run 组以复用 Halo 匿名角色的公开访问权限
        // 匿名角色规则：apiGroups: ["api.halo.run"], resources: ["*"], verbs: ["*"]
        return new GroupVersion("api.halo.run", "v1alpha1");
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
        ChatRequest chatReq = parseQueryRequest(request);
        return doStreamChat(chatReq.message, chatReq.history);
    }

    /** 流式对话核心逻辑 — GET 和 POST 路由共用 */
    private Mono<ServerResponse> doStreamChat(String message, List<Map<String, String>> history) {
        return chatService.chatStreamWithCitations(message, history)
            .flatMap(chatResp -> {
                // 1) citations 首帧（如果有引用来源）
                Flux<ServerSentEvent<String>> citationFrame = Flux.empty();
                if (chatResp.citations() != null && !chatResp.citations().isEmpty()) {
                    String citationJson = toJsonSafe(chatResp.citations());
                    citationFrame = Flux.just(
                        ServerSentEvent.<String>builder()
                            .event("citations")
                            .data(citationJson)
                            .build()
                    );
                }

                // 2) 真流式 token 帧：用 JSON 包一层避免 trim 吃空格、换行被拆多行 data
                Flux<ServerSentEvent<String>> tokenFrames = chatResp.stream()
                    .filter(token -> token != null && !token.isEmpty())
                    .map(token -> ServerSentEvent.<String>builder()
                        .data(wrapToken(token))
                        .build());

                // 3) [DONE] 终止帧
                Flux<ServerSentEvent<String>> doneFrame = Flux.just(
                    ServerSentEvent.<String>builder().data("[DONE]").build()
                );

                Flux<ServerSentEvent<String>> sseStream = citationFrame
                    .concatWith(tokenFrames)
                    .concatWith(doneFrame)
                    .onErrorResume(e -> {
                        log.error("[PublicChatEndpoint] 流式输出中断: {}", e.getMessage());
                        return Flux.just(
                            ServerSentEvent.<String>builder()
                                .data(wrapToken("\n\n[AI 服务异常，已中断]"))
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
                        .data(wrapToken("抱歉，AI 服务暂时不可用，请稍后重试。"))
                        .build(),
                    ServerSentEvent.<String>builder().data("[DONE]").build()
                );
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(errStream, ServerSentEvent.class);
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

    /** 把 citations 列表序列化成 JSON，失败则返回空数组 */
    private String toJsonSafe(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Mono<ServerResponse> handleChat(ServerRequest request) {
        ChatRequest chatReq = parseQueryRequest(request);
        return doChat(chatReq.message, chatReq.history);
    }

    /** 非流式对话核心逻辑 — GET 和 POST 路由共用 */
    private Mono<ServerResponse> doChat(String message, List<Map<String, String>> history) {
        return chatService.chat(message, history)
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
     * 流式对话 — POST 版本（body 传 history，避开 Netty 4KB URL 上限）
     *
     * Body 格式：{"message":"...", "history":[{"role":"user","content":"..."}, ...]}
     */
    private Mono<ServerResponse> handleStreamChatPost(ServerRequest request) {
        return request.bodyToMono(ChatRequestBody.class)
            .defaultIfEmpty(new ChatRequestBody("", List.of()))
            .flatMap(body -> doStreamChat(
                body.message() != null ? body.message() : "",
                body.history() != null ? body.history() : List.of()
            ));
    }

    private Mono<ServerResponse> handleChatPost(ServerRequest request) {
        return request.bodyToMono(ChatRequestBody.class)
            .defaultIfEmpty(new ChatRequestBody("", List.of()))
            .flatMap(body -> doChat(
                body.message() != null ? body.message() : "",
                body.history() != null ? body.history() : List.of()
            ));
    }

    /**
     * 返回浮窗配置 — 供前端 JS 调用
     */
    private Mono<ServerResponse> handleWidgetConfig(ServerRequest request) {
        return aiProperties.getChatConfig()
            .flatMap(chatConfig -> {
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
                    ? chatConfig.getWidgetThemeColor() : "#4F46E5");
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
                body.put("triggerAlign",
                    "manual".equals(triggerAlign) ? "manual" : "auto");
                body.put("triggerOffsetY",
                    chatConfig.getWidgetTriggerOffsetY() > 0 ? chatConfig.getWidgetTriggerOffsetY() : 24);
                body.put("stream", chatConfig.isStreamOutput());
                body.put("allowGuest", chatConfig.isAllowGuest());
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
            });
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
        return new ChatRequest(message, history);
    }

    record ChatRequest(String message, List<Map<String, String>> history) {}

    /** POST body — JSON 反序列化目标 */
    record ChatRequestBody(String message, List<Map<String, String>> history) {}
}

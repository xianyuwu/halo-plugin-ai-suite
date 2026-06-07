package run.halo.ai.assistant.endpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.service.ChatLogger;
import run.halo.ai.assistant.service.ChatLogger.ChatLogEntry;
import run.halo.ai.assistant.service.ChatLogger.FeedbackFilter;
import run.halo.ai.assistant.service.ChatLogger.LogFilter;
import run.halo.ai.assistant.service.ChatLogger.PageResult;
import run.halo.ai.assistant.service.ChatLogger.StatsResult;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台访客问答日志 + 反馈管理 API.
 *
 * <p>所有路由都在 {@code /apis/console.api.ai-assistant.halo.run/v1alpha1/chat-logs/...} 前缀,
 * 由 Halo 自动要求管理员认证.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleChatLogEndpoint implements CustomEndpoint {

    private final ChatLogger chatLogger;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/chat-logs", this::handleList)
            .GET("/chat-logs/stats", this::handleStats)
            .GET("/chat-logs/{id}", this::handleGet)
            .DELETE("/chat-logs/{id}", this::handleDelete)
            .POST("/chat-logs/clear", this::handleClear)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-assistant.halo.run", "v1alpha1");
    }

    /** GET /chat-logs?page=0&size=20&from=&to=&model=&feedbackType= */
    private Mono<ServerResponse> handleList(ServerRequest request) {
        int page = parseInt(request.queryParam("page"), 0);
        int size = Math.min(100, Math.max(1, parseInt(request.queryParam("size"), 20)));
        Instant from = parseInstant(request.queryParam("from").orElse(null));
        Instant to = parseInstant(request.queryParam("to").orElse(null));
        String model = request.queryParam("model").orElse("");
        FeedbackFilter fb = parseFbFilter(request.queryParam("feedbackType").orElse("all"));
        String question = request.queryParam("question").orElse("");
        LogFilter filter = new LogFilter(from, to, model.isEmpty() ? null : model, fb,
            question.isEmpty() ? null : question);

        return chatLogger.listLogs(filter, page, size)
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toJson(result)))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    /** GET /chat-logs/stats */
    private Mono<ServerResponse> handleStats(ServerRequest request) {
        return chatLogger.getStats()
            .map(this::toJson)
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    /** GET /chat-logs/{id} */
    private Mono<ServerResponse> handleGet(ServerRequest request) {
        String id = request.pathVariable("id");
        return chatLogger.getLog(id)
            .flatMap(log -> log == null
                ? ServerResponse.notFound().build()
                : ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toJson(log)))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    /** DELETE /chat-logs/{id} */
    private Mono<ServerResponse> handleDelete(ServerRequest request) {
        String id = request.pathVariable("id");
        return chatLogger.deleteLog(id)
            .then(ServerResponse.ok().bodyValue(Map.of("deleted", true)))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("deleted", false, "error", e.getMessage())));
    }

    /** POST /chat-logs/clear */
    private Mono<ServerResponse> handleClear(ServerRequest request) {
        return chatLogger.clearAll()
            .then(ServerResponse.ok().bodyValue(Map.of("cleared", true)))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("cleared", false, "error", e.getMessage())));
    }

    // ===== 序列化 helper =====

    private Map<String, Object> toJson(ChatLogEntry log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.id());
        m.put("timestamp", log.timestamp() != null ? log.timestamp().toString() : null);
        m.put("ip", log.ip());
        m.put("userAgent", log.userAgent());
        m.put("question", log.question());
        m.put("answer", log.answer());
        m.put("model", log.model());
        m.put("citations", log.citations() != null ? log.citations() : java.util.List.of());
        if (log.feedback() != null) {
            Map<String, Object> fb = new LinkedHashMap<>();
            fb.put("type", log.feedback().type());
            fb.put("comment", log.feedback().comment());
            fb.put("timestamp", log.feedback().timestamp() != null
                ? log.feedback().timestamp().toString() : null);
            m.put("feedback", fb);
        } else {
            m.put("feedback", null);
        }
        m.put("traceIntent", log.traceIntent());
        m.put("traceStagesJson", log.traceStagesJson());
        return m;
    }

    private Map<String, Object> toJson(PageResult<ChatLogEntry> page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", page.items().stream().map(this::toJson).toList());
        m.put("total", page.total());
        m.put("page", page.page());
        m.put("size", page.size());
        return m;
    }

    private Map<String, Object> toJson(StatsResult stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalLogs", stats.totalLogs());
        m.put("totalByModel", stats.totalByModel());
        Map<String, Long> dist = new LinkedHashMap<>();
        dist.put("like", stats.likes());
        dist.put("dislike", stats.dislikes());
        dist.put("none", stats.none());
        m.put("feedbackDistribution", dist);
        m.put("last7Days", stats.last7Days());
        m.put("todayNew", stats.todayNew());
        m.put("dislikeRate", stats.dislikeRate());
        // 昨日对比基线 — 前端用于 "vs 昨日 ±N%" 渲染
        Map<String, Object> yest = new LinkedHashMap<>();
        yest.put("newLogs", stats.yesterday() != null ? stats.yesterday().newLogs() : 0);
        yest.put("dislikes", stats.yesterday() != null ? stats.yesterday().dislikes() : 0);
        m.put("yesterday", yest);
        return m;
    }

    // ===== 解析 helper =====

    private int parseInt(java.util.Optional<String> opt, int def) {
        if (opt.isEmpty()) return def;
        try {
            return Integer.parseInt(opt.get());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            log.debug("[ConsoleChatLogEndpoint] 解析 Instant 失败: {}", s);
            return null;
        }
    }

    private FeedbackFilter parseFbFilter(String s) {
        if (s == null) return FeedbackFilter.ALL;
        return switch (s.toLowerCase()) {
            case "like" -> FeedbackFilter.LIKE;
            case "dislike" -> FeedbackFilter.DISLIKE;
            case "none" -> FeedbackFilter.NONE;
            default -> FeedbackFilter.ALL;
        };
    }
}

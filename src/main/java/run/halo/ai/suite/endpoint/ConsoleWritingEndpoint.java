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
import run.halo.ai.suite.service.WritingService;
import run.halo.ai.suite.service.WritingService.AssistEvent;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.Map;

/**
 * AI 写作辅助 API — Halo 文章编辑器内嵌入口。
 *
 * <ul>
 *   <li>{@code POST /writing/assist}        非流式（fallback）</li>
 *   <li>{@code POST /writing/assist/stream} SSE 流式（主路径）</li>
 * </ul>
 *
 * <h3>SSE 协议</h3>
 * <ul>
 *   <li>正常 token：{@code data: <token>}</li>
 *   <li>流结束：{@code data: [DONE]}</li>
 *   <li>错误：{@code event: error\ndata: <message>} 后接 {@code data: [DONE]}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleWritingEndpoint implements CustomEndpoint {

    private final WritingService writingService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
            .POST("/writing/assist", this::handleAssist)
            .POST("/writing/assist/stream", this::handleAssistStream)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> handleAssist(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                AssistRequest req = parseBody(body);
                return writingService.assist(req.text, req.action, req.instruction)
                    .flatMap(result -> {
                        if (result != null && result.startsWith("AI 调用失败")) {
                            return ServerResponse.status(502)
                                .bodyValue(Map.of("error", result));
                        }
                        return ServerResponse.ok()
                            .bodyValue(Map.of("result", result));
                    });
            });
    }

    private Mono<ServerResponse> handleAssistStream(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                AssistRequest req = parseBody(body);
                Flux<ServerSentEvent<String>> events = writingService
                    .assistStream(req.text, req.action, req.instruction)
                    .map(this::toSse);

                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(events, ServerSentEvent.class);
            });
    }

    /**
     * 把 {@link AssistEvent} 映射为 SSE 事件。
     *
     * <ul>
     *   <li>Token → {@code data: <text>}</li>
     *   <li>Done → {@code data: [DONE]}</li>
     *   <li>Error → {@code event: error, data: <msg>} 后接 {@code data: [DONE]}</li>
     * </ul>
     */
    private ServerSentEvent<String> toSse(AssistEvent event) {
        if (event instanceof AssistEvent.Token t) {
            return ServerSentEvent.<String>builder().data(t.text()).build();
        }
        if (event instanceof AssistEvent.Done) {
            return ServerSentEvent.<String>builder().data("[DONE]").build();
        }
        if (event instanceof AssistEvent.Error e) {
            // 单条事件承载两个 data 字段不标准，拆成两条：先 error 事件，再 [DONE] 收尾
            // 调用方应按 stream 处理（见 onErrorResume + concatWith in WritingService）
            return ServerSentEvent.<String>builder()
                .event("error")
                .data(e.message())
                .build();
        }
        return ServerSentEvent.<String>builder().data("").build();
    }

    private AssistRequest parseBody(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return new AssistRequest(
                root.path("text").asText(""),
                root.path("action").asText("polish"),
                root.path("instruction").asText("")
            );
        } catch (Exception e) {
            return new AssistRequest("", "polish", "");
        }
    }

    record AssistRequest(String text, String action, String instruction) {}
}

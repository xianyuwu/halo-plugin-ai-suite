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
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.service.WritingService;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.Map;

/**
 * AI 写作辅助 API
 *
 * - POST /writing/assist      非流式
 * - POST /writing/assist/stream  SSE 流式
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
        return new GroupVersion("console.api.ai-assistant.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> handleAssist(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                AssistRequest req = parseBody(body);
                return writingService.assist(req.text, req.action, req.instruction)
                    .flatMap(result -> ServerResponse.ok()
                        .bodyValue(Map.of("result", result)));
            });
    }

    private Mono<ServerResponse> handleAssistStream(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                AssistRequest req = parseBody(body);
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(
                        writingService.assistStream(req.text, req.action, req.instruction)
                            .map(token -> ServerSentEvent.<String>builder().data(token).build()),
                        ServerSentEvent.class
                    );
            });
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

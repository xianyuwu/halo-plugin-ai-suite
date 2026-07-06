package cn.rainwu.halo.ai.suite.endpoint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient.StreamEvent;
import cn.rainwu.halo.ai.suite.rag.PipelineTrace;
import cn.rainwu.halo.ai.suite.service.ChatLogger;
import cn.rainwu.halo.ai.suite.service.ChatService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PublicChatEndpointTest {

    private final PublicChatEndpoint endpoint = new PublicChatEndpoint(
        mock(ChatService.class), mock(AIProperties.class), mock(ChatLogger.class));
    private final WebTestClient client = WebTestClient
        .bindToRouterFunction(endpoint.endpoint())
        .build();

    @Test
    void streamRouteAcceptsPostJsonBody() {
        client.post()
            .uri("/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                .contains("message 必填", "[DONE]"));
    }

    @Test
    void nonStreamRouteAcceptsPostJsonBody() {
        client.post()
            .uri("/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.error").isEqualTo("message 必填");
    }

    @Test
    void legacyGetChatRoutesAreRemoved() {
        client.get().uri("/chat/stream?message=test")
            .exchange()
            .expectStatus().isNotFound();

        client.get().uri("/chat?message=test")
            .exchange()
            .expectStatus().isNotFound();

        client.get().uri("/chat/feedback?logId=test&type=like")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void feedbackRouteOnlyAcceptsPost() {
        client.post().uri("/chat/feedback")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.error").isEqualTo("logId 必填");
    }

    @Test
    void v1alpha2CompatibilityEndpointDelegatesChatRoutes() {
        PublicChatV1alpha2Endpoint compatibilityEndpoint =
            new PublicChatV1alpha2Endpoint(endpoint);
        WebTestClient compatibilityClient = WebTestClient
            .bindToRouterFunction(compatibilityEndpoint.endpoint())
            .build();

        org.assertj.core.api.Assertions.assertThat(compatibilityEndpoint.groupVersion())
            .isEqualTo(new run.halo.app.extension.GroupVersion(
                "api.ai-suite.halo.run", "v1alpha2"));
        compatibilityClient.post()
            .uri("/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void streamSeparatesReasoningEventsFromAnswerTokens() {
        ChatService chatService = mock(ChatService.class);
        AIProperties properties = mock(AIProperties.class);
        ChatLogger logger = mock(ChatLogger.class);
        PublicChatEndpoint reasoningEndpoint = new PublicChatEndpoint(chatService, properties, logger);
        WebTestClient reasoningClient = WebTestClient
            .bindToRouterFunction(reasoningEndpoint.endpoint())
            .build();

        AIProperties.ChatConfig config = new AIProperties.ChatConfig();
        config.setAllowGuest(true);
        config.setAllowVisitorReasoning(true);
        config.setReasoningDefaultEnabled(true);
        when(properties.getChatConfig()).thenReturn(Mono.just(config));
        Flux<StreamEvent> events = Flux.just(
            new StreamEvent(StreamEvent.REASONING_START, ""),
            new StreamEvent(StreamEvent.REASONING_DELTA, "private analysis"),
            new StreamEvent(StreamEvent.REASONING_END, ""),
            StreamEvent.text("final answer"));
        when(chatService.chatStreamWithDebug(eq("hello"), eq(List.of()), any(), any(),
            eq("enabled")))
            .thenReturn(Mono.just(new ChatService.DebugChatResponse(
                Flux.just("final answer"), List.of(), null,
                new PipelineTrace("hello", "NORMAL_CHAT"), events)));

        reasoningClient.post()
            .uri("/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("{\"message\":\"hello\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:reasoning_start", "event:reasoning_delta",
                    "private analysis", "final answer", "[DONE]"));
    }
}

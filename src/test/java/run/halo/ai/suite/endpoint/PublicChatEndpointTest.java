package run.halo.ai.suite.endpoint;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.service.ChatLogger;
import run.halo.ai.suite.service.ChatService;

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
    }
}

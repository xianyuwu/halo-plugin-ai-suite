package cn.rainwu.halo.ai.suite.endpoint;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.service.ChatService;

class SearchAnswerEndpointTest {

    private final SearchAnswerEndpoint endpoint = new SearchAnswerEndpoint(
        mock(ChatService.class), mock(AIProperties.class));
    private final WebTestClient client = WebTestClient
        .bindToRouterFunction(endpoint.endpoint())
        .build();

    @Test
    void routeAcceptsPostJsonBody() {
        client.post()
            .uri("/search/answer")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                .contains("请输入搜索关键词", "[DONE]"));
    }

    @Test
    void legacyGetRouteIsRemoved() {
        client.get()
            .uri("/search/answer?keyword=test")
            .exchange()
            .expectStatus().isNotFound();
    }
}

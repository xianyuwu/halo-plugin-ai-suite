package cn.rainwu.halo.ai.suite.endpoint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.service.ChatService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    @Test
    void streamFailureAfterPartialTextSendsExplicitErrorEvent() {
        var config = mock(AIProperties.class);
        var searchConfig = new AIProperties.SearchConfig();
        searchConfig.setEnabled(true);
        searchConfig.setShowAiAnswer(true);
        when(config.getSearchConfig()).thenReturn(Mono.just(searchConfig));
        var chat = mock(ChatService.class);
        when(chat.searchAnswerStreamWithCitations("作者在海南玩了几天？"))
            .thenReturn(Mono.just(new ChatService.ChatResponse(
                Flux.concat(Flux.just("前半句"), Flux.error(new IllegalStateException("broken stream"))),
                List.of(), null)));
        var client = WebTestClient.bindToRouterFunction(
            new SearchAnswerEndpoint(chat, config).endpoint()).build();

        client.post().uri("/search/answer")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue("{\"keyword\":\"作者在海南玩了几天？\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                .contains("前半句", "event:error", "内容可能不完整", "[DONE]"));
    }
}

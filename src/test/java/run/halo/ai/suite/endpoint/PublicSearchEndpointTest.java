package run.halo.ai.suite.endpoint;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.rag.LuceneIndexService;
import run.halo.app.extension.ReactiveExtensionClient;

class PublicSearchEndpointTest {

    private final PublicSearchEndpoint endpoint = new PublicSearchEndpoint(
        mock(LuceneIndexService.class), mock(ReactiveExtensionClient.class),
        mock(AIProperties.class));
    private final WebTestClient client = WebTestClient
        .bindToRouterFunction(endpoint.endpoint())
        .build();

    @Test
    void legacyResultsRouteIsRemoved() {
        client.get()
            .uri("/search/results?keyword=test")
            .exchange()
            .expectStatus().isNotFound();
    }
}

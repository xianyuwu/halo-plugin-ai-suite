package cn.rainwu.halo.ai.suite.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.rag.LuceneIndexService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.SearchOption;
import run.halo.app.search.SearchResult;
import run.halo.app.search.SearchService;

class PublicSearchEndpointTest {

    private final LuceneIndexService luceneIndexService = mock(LuceneIndexService.class);
    private final AIProperties aiProperties = mock(AIProperties.class);
    private final SearchService searchService = mock(SearchService.class);
    private final PublicSearchEndpoint endpoint = new PublicSearchEndpoint(
        luceneIndexService, mock(ReactiveExtensionClient.class), aiProperties, searchService);
    private final WebTestClient client = WebTestClient
        .bindToRouterFunction(endpoint.endpoint())
        .build();

    @Test
    void usesHaloSearchAndKeepsOnlyPublicPosts() {
        enableSearch(3);
        HaloDocument post = post("post-one", "<mark>DeepSeek</mark> &lt;One&gt;");
        post.setDescription("About <mark>DeepSeek</mark> &lt;script&gt;alert(1)&lt;/script&gt;");
        HaloDocument hidden = post("hidden", "Hidden");
        hidden.setExposed(false);
        HaloDocument otherType = post("page", "Page");
        otherType.setType("singlepage.content.halo.run");
        SearchResult result = new SearchResult();
        result.setHits(List.of(post, hidden, otherType));
        result.setTotal(9L);
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri("/search/halo-results?keyword=DeepSeek")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.source").isEqualTo("halo")
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.articles.length()").isEqualTo(1)
            .jsonPath("$.articles[0].postId").isEqualTo("post-one")
            .jsonPath("$.articles[0].title").isEqualTo("DeepSeek <One>")
            .jsonPath("$.articles[0].url").isEqualTo("/archives/post-one")
            .jsonPath("$.articles[0].snippet")
            .isEqualTo("About <mark>DeepSeek</mark> &lt;script&gt;alert(1)&lt;/script&gt;");

        ArgumentCaptor<SearchOption> options = ArgumentCaptor.forClass(SearchOption.class);
        verify(searchService).search(options.capture());
        SearchOption option = options.getValue();
        assertThat(option.getKeyword()).isEqualTo("DeepSeek");
        assertThat(option.getLimit()).isEqualTo(3);
        assertThat(option.getFilterExposed()).isTrue();
        assertThat(option.getFilterPublished()).isTrue();
        assertThat(option.getFilterRecycled()).isFalse();
        assertThat(option.getIncludeTypes()).containsExactly("post.content.halo.run");
        assertThat(option.getHighlightPreTag()).isEqualTo("<mark>");
        assertThat(option.getHighlightPostTag()).isEqualTo("</mark>");
        verifyNoInteractions(luceneIndexService);
    }

    @Test
    void fallsBackToPluginIndexWhenHaloSearchFails() throws Exception {
        enableSearch(10);
        when(searchService.search(any())).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        when(luceneIndexService.searchKeyword(anyString(), anyInt())).thenReturn(List.of());

        client.get().uri("/search/halo-results?keyword=Halo")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.source").isEqualTo("lucene")
            .jsonPath("$.total").isEqualTo(0);

        verify(luceneIndexService).searchKeyword("Halo", 50);
    }

    @Test
    void emptyHaloResultDoesNotFallBackToUnrelatedPluginHits() {
        enableSearch(10);
        SearchResult result = new SearchResult();
        result.setHits(List.of());
        result.setTotal(0L);
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri("/search/halo-results?keyword=rare-word")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.source").isEqualTo("halo")
            .jsonPath("$.total").isEqualTo(0);

        verifyNoInteractions(luceneIndexService);
    }

    @Test
    void disabledSearchNeverCallsEitherEngine() {
        AIProperties.SearchConfig config = new AIProperties.SearchConfig();
        when(aiProperties.getSearchConfig()).thenReturn(Mono.just(config));

        client.get().uri("/search/halo-results?keyword=Halo")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.source").isEqualTo("disabled")
            .jsonPath("$.total").isEqualTo(0);

        verifyNoInteractions(searchService, luceneIndexService);
    }

    @Test
    void legacyResultsRouteIsRemoved() {
        client.get()
            .uri("/search/results?keyword=test")
            .exchange()
            .expectStatus().isNotFound();
    }

    private void enableSearch(int resultCount) {
        AIProperties.SearchConfig config = new AIProperties.SearchConfig();
        config.setEnabled(true);
        config.setResultCount(resultCount);
        when(aiProperties.getSearchConfig()).thenReturn(Mono.just(config));
    }

    private HaloDocument post(String name, String title) {
        HaloDocument hit = new HaloDocument();
        hit.setMetadataName(name);
        hit.setType("post.content.halo.run");
        hit.setTitle(title);
        hit.setPermalink("/archives/" + name);
        hit.setPublished(true);
        hit.setExposed(true);
        return hit;
    }
}

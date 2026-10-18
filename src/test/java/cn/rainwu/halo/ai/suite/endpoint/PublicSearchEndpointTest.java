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
            .jsonPath("$.questionMode").isEqualTo(false)
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.articles.length()").isEqualTo(1)
            .jsonPath("$.articles[0].postId").isEqualTo("post-one")
            .jsonPath("$.articles[0].title").isEqualTo("DeepSeek <One>")
            .jsonPath("$.articles[0].url").isEqualTo("/archives/post-one")
            .jsonPath("$.articles[0].snippet")
            .isEqualTo("About <mark>DeepSeek</mark>");

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
    void questionReranksHaloCandidatesAndKeepsAllArticles() {
        enableSearch(10);
        HaloDocument incidental = post("crypto", "国密算法");
        incidental.setDescription("<mark>中国</mark>密码技术");
        HaloDocument travel = post("travel", "海南自驾游");
        travel.setContent("分享<mark>好玩儿的地方</mark>");
        SearchResult result = new SearchResult();
        result.setHits(List.of(incidental, travel));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri(builder -> builder.path("/search/halo-results")
                .queryParam("keyword", "中国有哪些好玩儿的地方？").build())
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.questionMode").isEqualTo(true)
            .jsonPath("$.total").isEqualTo(2)
            .jsonPath("$.articles[0].postId").isEqualTo("travel")
            .jsonPath("$.articles[1].postId").isEqualTo("crypto");
    }

    @Test
    void severalTitleMatchesOutrankOneLongGenericTerm() {
        enableSearch(10);
        HaloDocument calico = post("calico", "如何在<mark>Kubernetes</mark>中配置Calico路由反射器");
        calico.setDescription("<mark>Kubernetes</mark>网络教程");
        HaloDocument deployment = post("deployment",
            "通过yaml<mark>在原生</mark>k8s上<mark>部署</mark><mark>Halo</mark>开源<mark>博客</mark>系统");
        deployment.setDescription("<mark>在原生</mark><mark>Kubernetes</mark>上<mark>部署</mark>Halo博客");
        SearchResult result = new SearchResult();
        result.setHits(List.of(calico, deployment));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri(builder -> builder.path("/search/halo-results")
                .queryParam("keyword", "Halo博客在原生Kubernetes上怎么部署？").build())
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.questionMode").isEqualTo(true)
            .jsonPath("$.articles[0].postId").isEqualTo("deployment")
            .jsonPath("$.articles[1].postId").isEqualTo("calico");
    }

    @Test
    void questionWithoutHighlightsKeepsHaloOrder() {
        enableSearch(10);
        SearchResult result = new SearchResult();
        result.setHits(List.of(post("first", "第一篇"), post("second", "第二篇")));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri(builder -> builder.path("/search/halo-results")
                .queryParam("keyword", "作者在海南玩了几天").build())
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.questionMode").isEqualTo(true)
            .jsonPath("$.clearMatch").isEqualTo(false)
            .jsonPath("$.articles[0].postId").isEqualTo("first")
            .jsonPath("$.articles[1].postId").isEqualTo("second");
    }

    @Test
    void weakQuestionCandidatesRemainAvailableButAreMarkedUnclear() {
        enableSearch(10);
        HaloDocument incidental = post("math", "数学笔记");
        incidental.setContent("<mark>博客</mark>中的<mark>计算</mark>示例");
        SearchResult result = new SearchResult();
        result.setHits(List.of(incidental));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri(builder -> builder.path("/search/halo-results")
                .queryParam("keyword", "博客里有没有写量子计算？").build())
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.clearMatch").isEqualTo(false)
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.articles[0].postId").isEqualTo("math");
    }

    @Test
    void splitQuestionFillerDoesNotMakeUnrelatedHitsClear() {
        enableSearch(10);
        HaloDocument first = post("blog", "<mark>博客</mark>搭建");
        HaloDocument second = post("travel", "旅行日记");
        second.setContent("这<mark>里有</mark>风景，却<mark>没有</mark>量子计算内容");
        SearchResult result = new SearchResult();
        result.setHits(List.of(first, second));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri(builder -> builder.path("/search/halo-results")
                .queryParam("keyword", "博客里有没有写量子计算？").build())
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.clearMatch").isEqualTo(false)
            .jsonPath("$.total").isEqualTo(2);
    }

    @Test
    void specificQuestionHighlightIsClear() {
        enableSearch(10);
        HaloDocument travel = post("travel", "海南自驾游");
        travel.setContent("去过<mark>好玩儿的地方</mark>");
        SearchResult result = new SearchResult();
        result.setHits(List.of(travel));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri(builder -> builder.path("/search/halo-results")
                .queryParam("keyword", "中国有哪些好玩儿的地方？").build())
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.clearMatch").isEqualTo(true);
    }

    @Test
    void historicalArticleDoesNotAnswerCurrentWeather() {
        enableSearch(10);
        HaloDocument travel = post("travel", "北京旅行天气");
        travel.setContent("<mark>北京今天天气</mark>不错");
        SearchResult result = new SearchResult();
        result.setHits(List.of(travel));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri(builder -> builder.path("/search/halo-results")
                .queryParam("keyword", "今天北京天气怎么样？").build())
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.clearMatch").isEqualTo(false)
            .jsonPath("$.total").isEqualTo(1);
    }

    @Test
    void removesArticleMarkupButKeepsHighlightedText() {
        enableSearch(10);
        HaloDocument post = post("travel", "海南游记");
        post.setContent("&lt;p&gt;这片&lt;mark&gt;海滩&lt;/mark&gt;很美&lt;/p&gt;"
            + "<img src=\"photo.jpg\" alt=\"photo\">"
            + "<script>alert(1)</script><style>body{display:none}</style>");
        SearchResult result = new SearchResult();
        result.setHits(List.of(post));
        when(searchService.search(any())).thenReturn(Mono.just(result));

        client.get().uri("/search/halo-results?keyword=海南游记")
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.articles[0].snippet").isEqualTo("这片<mark>海滩</mark>很美");
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

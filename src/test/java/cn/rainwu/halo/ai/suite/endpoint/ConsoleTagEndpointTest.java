package cn.rainwu.halo.ai.suite.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.service.TagGenerationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;

class ConsoleTagEndpointTest {
    @Test
    void queriesOnlyRequestedPageAndItsTags() {
        var extensions = mock(ReactiveExtensionClient.class);
        var endpoint = new ConsoleTagEndpoint(extensions, mock(TagGenerationService.class));
        var web = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
        Post post = post("post-a", "Halo AI", List.of("tag-halo", "tag-missing"));
        when(extensions.listBy(eq(Post.class), any(), any()))
            .thenReturn(Mono.just(new ListResult<>(2, 2, 101, List.of(post))));
        when(extensions.listAll(eq(Tag.class), any(), eq(Sort.unsorted())))
            .thenReturn(Flux.just(tag("tag-halo", "Halo")));

        web.get().uri("/tags/articles?page=2&size=2&keyword=hAlO")
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page").isEqualTo(2)
            .jsonPath("$.size").isEqualTo(2)
            .jsonPath("$.total").isEqualTo(101)
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].title").isEqualTo("Halo AI")
            .jsonPath("$.items[0].tags[0]").isEqualTo("Halo")
            .jsonPath("$.items[0].tags[1]").isEqualTo("tag-missing");

        ArgumentCaptor<ListOptions> postOptions = ArgumentCaptor.forClass(ListOptions.class);
        ArgumentCaptor<PageRequest> pageRequest = ArgumentCaptor.forClass(PageRequest.class);
        verify(extensions).listBy(eq(Post.class), postOptions.capture(), pageRequest.capture());
        assertThat(postOptions.getValue().toString())
            .contains("spec.deleted", "metadata.deletionTimestamp", "spec.title", "hAlO");
        assertThat(pageRequest.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageRequest.getValue().getSort().getOrderFor("metadata.creationTimestamp")
            .getDirection()).isEqualTo(Sort.Direction.DESC);
        ArgumentCaptor<ListOptions> tagOptions = ArgumentCaptor.forClass(ListOptions.class);
        verify(extensions).listAll(eq(Tag.class), tagOptions.capture(), eq(Sort.unsorted()));
        assertThat(tagOptions.getValue().toString()).contains("metadata.name", "tag-halo",
            "tag-missing");
        verifyNoMoreInteractions(extensions);
    }

    @Test
    void emptyPageDoesNotReadTags() {
        var extensions = mock(ReactiveExtensionClient.class);
        var endpoint = new ConsoleTagEndpoint(extensions, mock(TagGenerationService.class));
        var web = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
        when(extensions.listBy(eq(Post.class), any(), any()))
            .thenReturn(Mono.just(new ListResult<>(3, 10, 17, List.of())));

        web.get().uri("/tags/articles?page=3&size=10")
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.total").isEqualTo(17)
            .jsonPath("$.items.length()").isEqualTo(0);

        verify(extensions).listBy(eq(Post.class), any(), any());
        verifyNoMoreInteractions(extensions);
    }

    private static Post post(String name, String title, List<String> tags) {
        Post post = new Post();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.parse("2026-10-13T00:00:00Z"));
        metadata.setLabels(Map.of(Post.PUBLISHED_LABEL, "true"));
        post.setMetadata(metadata);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle(title);
        spec.setTags(tags);
        post.setSpec(spec);
        return post;
    }

    private static Tag tag(String name, String displayName) {
        Tag tag = new Tag();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        tag.setMetadata(metadata);
        Tag.TagSpec spec = new Tag.TagSpec();
        spec.setDisplayName(displayName);
        tag.setSpec(spec);
        return tag;
    }
}

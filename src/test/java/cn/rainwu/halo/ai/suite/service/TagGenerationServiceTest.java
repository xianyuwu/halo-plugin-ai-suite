package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class TagGenerationServiceTest {
    @Test
    void generatesSuggestionsWithMockModelWithoutChangingPost() {
        var client = mock(ReactiveExtensionClient.class);
        var contentService = mock(PostContentService.class);
        var llm = mock(LlmClient.class);
        var properties = mock(AIProperties.class);
        var service = new TagGenerationService(client, contentService, llm, properties);
        Post post = post("post-a", "Halo AI 文章", java.util.List.of("tag-halo"));
        Tag halo = tag("tag-halo", "Halo");
        var models = new AIProperties.ModelConfig();
        models.setAiFoundationChatModelName("mock-model");
        when(client.fetch(Post.class, "post-a")).thenReturn(Mono.just(post));
        when(client.list(eq(Tag.class), any(), isNull())).thenReturn(Flux.just(halo));
        when(properties.getModelConfig()).thenReturn(Mono.just(models));
        when(properties.getWritingConfig()).thenReturn(Mono.just(new AIProperties.WritingConfig()));
        when(contentService.getHeadContent("post-a"))
            .thenReturn(Mono.just(ContentWrapper.builder().raw("<p>正文内容</p>").build()));
        when(llm.chatInternal(eq("mock-model"), anyList(), eq(0.2f), eq(2048),
            isNull(), eq(UsageScenario.TAG_GENERATE), eq("disabled")))
            .thenReturn(Mono.just("{\"tags\":[\"Halo\",\"AI 写作\"]}"));

        var result = service.generate("post-a", 2).block();

        assertThat(result).isNotNull();
        assertThat(result.tags()).containsExactly(
            new TagGenerationService.SuggestedTag("Halo", true),
            new TagGenerationService.SuggestedTag("AI 写作", false));
        assertThat(result.currentTags()).containsExactly("Halo");
        verify(llm).chatInternal(eq("mock-model"), argThat(messages ->
                messages.getFirst().get("content").contains("正文内容")
                    && messages.getFirst().get("content").contains("Halo AI 文章")),
            eq(0.2f), eq(2048), isNull(), eq(UsageScenario.TAG_GENERATE), eq("disabled"));
        verify(client, never()).update(any(Post.class));
    }

    @Test
    void createsNewTagAndPreservesExistingAssociationsWhenApplying() {
        var client = mock(ReactiveExtensionClient.class);
        var service = new TagGenerationService(client, mock(PostContentService.class),
            mock(LlmClient.class), mock(AIProperties.class));
        Post post = post("post-a", "文章", java.util.List.of("tag-halo"));
        Tag halo = tag("tag-halo", "Halo");
        when(client.fetch(Post.class, "post-a")).thenReturn(Mono.just(post));
        when(client.list(eq(Tag.class), any(), isNull())).thenReturn(Flux.just(halo));
        when(client.create(any(Tag.class))).thenAnswer(call -> {
            Tag created = call.getArgument(0);
            created.getMetadata().setName("tag-new");
            return Mono.just(created);
        });
        when(client.update(any(Post.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));

        var result = service.apply("post-a", java.util.List.of("Halo", "AI 写作")).block();

        assertThat(result).isNotNull();
        assertThat(result.tags()).containsExactly("Halo", "AI 写作");
        assertThat(result.added()).isEqualTo(1);
        assertThat(post.getSpec().getTags()).containsExactly("tag-halo", "tag-new");
        verify(client).create(argThat((Tag created) ->
            "AI 写作".equals(created.getSpec().getDisplayName())));
        verify(client).update(post);
    }

    @Test
    void keepsExistingTagsAndAvoidsDuplicateAssociations() {
        assertThat(TagGenerationService.mergeTagIds(
            java.util.List.of("tag-a", "tag-b"),
            java.util.List.of("tag-b", "tag-c")))
            .containsExactly("tag-a", "tag-b", "tag-c");
    }

    @Test
    void parsesJsonAndReusesExistingDisplayName() {
        Tag existing = tag("Halo");
        var result = TagGenerationService.parseSuggestions(
            "{\"tags\":[\"halo\",\"AI 写作\",\"halo\"]}", 5,
            Map.of("halo", existing));
        assertThat(result).containsExactly(
            new TagGenerationService.SuggestedTag("Halo", true),
            new TagGenerationService.SuggestedTag("AI 写作", false));
    }

    @Test
    void acceptsFencedJsonAndLimitsCandidates() {
        var result = TagGenerationService.parseSuggestions(
            "```json\n{\"tags\":[\"Java\",\"Spring\",\"Halo\"]}\n```", 2, Map.of());
        assertThat(result).extracting(TagGenerationService.SuggestedTag::name)
            .containsExactly("Java", "Spring");
    }

    @Test
    void discardsInvalidNamesFromPlainTextResponse() {
        var result = TagGenerationService.parseSuggestions(
            "Java, <script>, Java, Spring", 5, Map.of());
        assertThat(result).extracting(TagGenerationService.SuggestedTag::name)
            .containsExactly("Java", "Spring");
    }

    private static Tag tag(String displayName) {
        return tag("tag-" + displayName, displayName);
    }

    private static Tag tag(String id, String displayName) {
        Tag tag = new Tag();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        tag.setMetadata(metadata);
        Tag.TagSpec spec = new Tag.TagSpec();
        spec.setDisplayName(displayName);
        tag.setSpec(spec);
        return tag;
    }

    private static Post post(String id, String title, java.util.List<String> tags) {
        Post post = mock(Post.class);
        Metadata metadata = new Metadata();
        metadata.setName(id);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle(title);
        spec.setTags(tags);
        when(post.getMetadata()).thenReturn(metadata);
        when(post.getSpec()).thenReturn(spec);
        return post;
    }
}

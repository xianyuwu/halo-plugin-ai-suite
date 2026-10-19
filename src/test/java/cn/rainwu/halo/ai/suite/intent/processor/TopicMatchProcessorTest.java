package cn.rainwu.halo.ai.suite.intent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.extension.IntentRoute.PipelineStep;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class TopicMatchProcessorTest {

    @Test
    void topicClassificationDisablesReasoningAndKeepsMetadataFallback() {
        ReactiveExtensionClient extensionClient = mock(ReactiveExtensionClient.class);
        LlmClient llm = mock(LlmClient.class);
        AIProperties properties = mock(AIProperties.class);
        TopicMatchProcessor processor = new TopicMatchProcessor(extensionClient, llm, properties);
        AIProperties.ModelConfig models = new AIProperties.ModelConfig();
        models.setAiFoundationChatModelName("chat-model");
        when(properties.getModelConfig()).thenReturn(Mono.just(models));
        Tag tag = mock(Tag.class);
        Metadata tagMetadata = new Metadata();
        tagMetadata.setName("tag-halo");
        Tag.TagSpec tagSpec = new Tag.TagSpec();
        tagSpec.setDisplayName("Halo");
        when(tag.getMetadata()).thenReturn(tagMetadata);
        when(tag.getSpec()).thenReturn(tagSpec);
        when(extensionClient.list(eq(Tag.class), any(), isNull())).thenReturn(Flux.just(tag));
        when(extensionClient.list(eq(Category.class), any(), isNull())).thenReturn(Flux.empty());
        when(llm.chat(eq("chat-model"), anyList(), eq(0.0f), eq(2048),
            eq(Map.of("type", "json_object")), isNull(),
            eq(UsageScenario.INTENT_PIPELINE), eq("disabled")))
            .thenReturn(Mono.error(new IllegalStateException("model returned no JSON")));
        Post post = mock(Post.class);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle("Halo 文章");
        spec.setTags(List.of("tag-halo"));
        when(post.getSpec()).thenReturn(spec);
        PipelineStep step = new PipelineStep();
        step.setParams(Map.of());

        List<Post> result = processor.process(List.of(post), step, "Halo", List.of()).block();

        assertThat(result).containsExactly(post);
        verify(llm).chat(eq("chat-model"), anyList(), eq(0.0f), eq(2048),
            eq(Map.of("type", "json_object")), isNull(),
            eq(UsageScenario.INTENT_PIPELINE), eq("disabled"));
    }
}

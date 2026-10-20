package cn.rainwu.halo.ai.suite.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.extension.IntentRoute;
import cn.rainwu.halo.ai.suite.extension.IntentRoute.PipelineStep;
import cn.rainwu.halo.ai.suite.intent.processor.LlmTitleFilterProcessor;
import cn.rainwu.halo.ai.suite.intent.processor.TopicMatchProcessor;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import cn.rainwu.halo.ai.suite.service.IntentDetector;
import cn.rainwu.halo.ai.suite.service.IntentRouteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class IntentModelSelectionTest {

    private final AIProperties properties = mock(AIProperties.class);
    private final List<String> selectedModels = new ArrayList<>();
    private final LlmClient llm = mock(LlmClient.class, invocation -> {
        if ("chat".equals(invocation.getMethod().getName())) {
            selectedModels.add(invocation.getArgument(0));
            return Mono.just(UsageScenario.INTENT_DETECT.equals(invocation.getArgument(6))
                ? "travel-route" : "{\"related\":[1]}");
        }
        return Answers.RETURNS_DEFAULTS.answer(invocation);
    });

    IntentModelSelectionTest() {
        AIProperties.ModelConfig models = new AIProperties.ModelConfig();
        models.setAiFoundationChatModelName("chat-model");
        models.setAiFoundationIntentModelName("intent-model");
        when(properties.getModelConfig()).thenReturn(Mono.just(models));
    }

    @Test
    void llmFallbackDetectsRouteWithIntentModel() {
        IntentRouteService routes = mock(IntentRouteService.class);
        IntentRoute route = new IntentRoute();
        Metadata metadata = new Metadata();
        metadata.setName("travel-route");
        route.setMetadata(metadata);
        IntentRoute.Spec spec = new IntentRoute.Spec();
        spec.setDisplayName("旅行文章");
        spec.setTriggerPatterns(List.of("^列出旅行文章$"));
        spec.setLlmFallback(true);
        route.setSpec(spec);
        when(routes.listEnabledIntents()).thenReturn(Mono.just(List.of(route)));
        assertThat(new IntentDetector(routes, llm, properties)
            .detect("最近有什么旅行内容", List.of()).block()).contains(route);
        assertThat(selectedModels).containsExactly("intent-model");
    }

    @Test
    void bothArticleClassifiersUseIntentModel() {
        Post post = mock(Post.class);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle("旅行游记");
        when(post.getSpec()).thenReturn(spec);
        PipelineStep step = new PipelineStep();
        step.setParams(Map.of());
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.list(eq(Tag.class), any(), isNull())).thenReturn(Flux.empty());
        when(client.list(eq(Category.class), any(), isNull())).thenReturn(Flux.empty());
        assertThat(new TopicMatchProcessor(client, llm, properties)
            .process(List.of(post), step, "旅行", List.of()).block()).containsExactly(post);
        assertThat(new LlmTitleFilterProcessor(llm, properties)
            .process(List.of(post), step, "旅行", List.of()).block()).containsExactly(post);

        assertThat(selectedModels).containsExactly("intent-model", "intent-model");
    }
}

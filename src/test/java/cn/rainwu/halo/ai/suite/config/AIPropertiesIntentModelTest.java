package cn.rainwu.halo.ai.suite.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

class AIPropertiesIntentModelTest {

    @Test
    void readsIntentModelAndFallsBackToChatModelWhenUnset() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        ConfigMap configMap = new ConfigMap();
        configMap.setData(Map.of("models", """
            {"aiFoundationChatModelName":"chat-model",
             "aiFoundationIntentModelName":"intent-model"}
            """));
        when(client.fetch(ConfigMap.class, "ai-suite-configmap"))
            .thenReturn(Mono.just(configMap));
        when(client.fetch(ConfigMap.class, "ai-assistant-configmap"))
            .thenReturn(Mono.empty());

        AIProperties.ModelConfig models = new AIProperties(client).getModelConfig().block();

        assertThat(models).isNotNull();
        assertThat(models.getAiFoundationIntentModelName()).isEqualTo("intent-model");
        assertThat(models.getEffectiveIntentModel()).isEqualTo("intent-model");
        models.setAiFoundationIntentModelName("");
        assertThat(models.getEffectiveIntentModel()).isEqualTo("chat-model");
    }
}

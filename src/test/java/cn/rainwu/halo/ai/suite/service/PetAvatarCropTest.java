package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PetAvatarCropTest {
    @Test void avatarCropSettingsRoundTripThroughChatConfig() {
        var client = org.mockito.Mockito.mock(run.halo.app.extension.ReactiveExtensionClient.class);
        var config = new run.halo.app.extension.ConfigMap();
        config.setData(java.util.Map.of("chat", "{\"widgetPetAvatarCrops\":\"{\\\"pet:a\\\":{\\\"size\\\":0.3}}\"}"));
        org.mockito.Mockito.when(client.fetch(run.halo.app.extension.ConfigMap.class, "ai-suite-configmap"))
            .thenReturn(reactor.core.publisher.Mono.just(config));
        org.mockito.Mockito.when(client.fetch(run.halo.app.extension.ConfigMap.class, "ai-assistant-configmap"))
            .thenReturn(reactor.core.publisher.Mono.empty());
        var chat = new cn.rainwu.halo.ai.suite.config.AIProperties(client).getChatConfig().block();
        assertThat(PetAvatarCrop.resolve(chat.getWidgetPetAvatarCrops(), "pet:a", null).size()).isEqualTo(0.3);
    }

    @Test void savedCropsAreScopedToEachPet() {
        String raw = "{\"pet:a\":{\"centerX\":0.6,\"centerY\":0.4,\"size\":0.3}}";
        assertThat(PetAvatarCrop.resolve(raw, "pet:a", null)).isEqualTo(new PetAvatarCrop(0.6, 0.4, 0.3));
        assertThat(PetAvatarCrop.resolve(raw, "pet:b", null)).isEqualTo(PetAvatarCrop.resolve("{}", "pet:b", null));
    }

    @Test void malformedAndMissingConfigUseFaceOrBuiltinDefaults() {
        var region = new PetStore.ExpressionRegion();
        region.setCenterX(0.55); region.setCenterY(0.3); region.setWidth(0.4); region.setHeight(0.2);
        assertThat(PetAvatarCrop.resolve("broken", "pet:a", region)).isEqualTo(new PetAvatarCrop(0.55, 0.3, 0.52));
        assertThat(PetAvatarCrop.resolve(null, "preset:cream-cat", null).size()).isEqualTo(0.64);
    }

    @Test void cropBoundsStayInsideMotherCanvasAndRejectNonFiniteValues() {
        assertThat(PetAvatarCrop.normalize(-1, 2, 0.4)).isEqualTo(new PetAvatarCrop(0.2, 0.8, 0.4));
        var crop = PetAvatarCrop.normalize(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN);
        assertThat(crop).isEqualTo(new PetAvatarCrop(0.5, 0.34, 0.52));
        assertThat(PetAvatarCrop.normalize(0.5, 0.5, 8).size()).isEqualTo(0.85);
    }
}

package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class PetStoreTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final PetStore store = new PetStore(client);

    private ConfigMap config(String json) {
        var cm = new ConfigMap(); cm.setMetadata(new Metadata()); cm.getMetadata().setName("ai-suite-configmap");
        cm.getMetadata().setVersion(1L); cm.setData(new LinkedHashMap<>()); cm.getData().put("pets", json);
        when(client.fetch(ConfigMap.class, "ai-suite-configmap")).thenReturn(Mono.just(cm));
        when(client.update(any(ConfigMap.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        return cm;
    }

    @Test void missingPetIsEmptyRatherThanReactorNullError() {
        config("[]"); assertThat(store.get("missing").block()).isNull();
        assertThat(store.get("missing").defaultIfEmpty(new PetStore.PetRecord()).block()).isNotNull();
    }

    @Test void corruptAndNonArrayDataNeverWriteOverOriginal() {
        for (String json : java.util.List.of("broken", "{}", "null")) {
            var cm = config(json); var pet = new PetStore.PetRecord(); pet.setId("new");
            assertThatThrownBy(() -> store.add(pet).block()).hasMessageContaining("配置损坏");
            assertThatThrownBy(() -> store.remove("old").block()).hasMessageContaining("配置损坏");
            assertThat(cm.getData().get("pets")).isEqualTo(json);
        }
        verify(client, never()).update(any(ConfigMap.class));
        verify(client, never()).create(any(ConfigMap.class));
    }

    @Test void optimisticConflictRefetchesAndModifiesLatestRecordWithoutDroppingOtherFields() {
        var cm = config("[{\"id\":\"a\",\"name\":\"first\"}]");
        when(client.update(any(ConfigMap.class))).thenAnswer(call -> {
            if (cm.getData().get("pets").contains("first")) {
                cm.getData().put("pets", "[{\"id\":\"a\",\"name\":\"other tab\",\"cleanupRevision\":1}]");
                return Mono.error(new org.springframework.dao.OptimisticLockingFailureException("conflict"));
            }
            return Mono.just(call.getArgument(0));
        });
        var result = store.modify("a", pet -> pet.setExpressionPrompt("new prompt")).block();
        assertThat(result.getName()).isEqualTo("other tab");
        assertThat(result.getCleanupRevision()).isEqualTo(1);
        assertThat(result.getExpressionPrompt()).isEqualTo("new prompt");
    }
}

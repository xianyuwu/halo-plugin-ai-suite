package run.halo.ai.suite.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次性迁移：将 ConfigMap 中存量的 API Key 迁移到 Halo Secret
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyMigration {

    private static final String CONFIG_MAP_NAME = "ai-suite-configmap";
    private static final String LEGACY_CONFIG_MAP_NAME = "ai-assistant-configmap";
    private static final String SECRET_NAME = "ai-suite-api-keys";
    private static final String LEGACY_SECRET_NAME = "ai-assistant-api-keys";
    private static final String[] API_KEY_FIELDS = {
        "chatApiKey", "embeddingApiKey", "rerankApiKey", "queryRewriteApiKey", "writingApiKey"
    };

    private final ReactiveExtensionClient extensionClient;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @PostConstruct
    void migrate() {
        // 延迟 5 秒，确保 Halo 扩展体系完全就绪
        Mono.delay(Duration.ofSeconds(5))
            .then(ensureConfigMapMigrated())
            .then(ensureSecretMigrated())
            .then(extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                .flatMap(this::migrateConfigMap))
            .onErrorResume(e -> {
                log.warn("[ApiKeyMigration] 异常: {}", e.getMessage());
                return Mono.empty();
            })
            .subscribe();
    }

    private Mono<Void> ensureConfigMapMigrated() {
        return extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .hasElement()
            .flatMap(exists -> {
                if (exists) return Mono.empty();
                return extensionClient.fetch(ConfigMap.class, LEGACY_CONFIG_MAP_NAME)
                    .flatMap(legacy -> {
                        ConfigMap cm = new ConfigMap();
                        cm.setMetadata(new Metadata());
                        cm.getMetadata().setName(CONFIG_MAP_NAME);
                        cm.setData(legacy.getData());
                        return extensionClient.create(cm)
                            .doOnSuccess(created -> log.info("[ApiKeyMigration] 已从旧 ConfigMap {} 迁移到 {}",
                                LEGACY_CONFIG_MAP_NAME, CONFIG_MAP_NAME))
                            .then();
                    });
            });
    }

    private Mono<Void> ensureSecretMigrated() {
        return extensionClient.fetch(Secret.class, SECRET_NAME)
            .hasElement()
            .flatMap(exists -> {
                if (exists) return Mono.empty();
                return extensionClient.fetch(Secret.class, LEGACY_SECRET_NAME)
                    .flatMap(legacy -> {
                        Secret secret = new Secret();
                        secret.setMetadata(new Metadata());
                        secret.getMetadata().setName(SECRET_NAME);
                        secret.setStringData(legacy.getStringData());
                        secret.setType(legacy.getType() != null ? legacy.getType() : "Opaque");
                        return extensionClient.create(secret)
                            .doOnSuccess(created -> log.info("[ApiKeyMigration] 已从旧 Secret {} 迁移到 {}",
                                LEGACY_SECRET_NAME, SECRET_NAME))
                            .then();
                    });
            });
    }

    private Mono<Void> migrateConfigMap(ConfigMap cm) {
        var data = cm.getData();
        if (data == null || data.isEmpty()) return Mono.empty();

        Map<String, String> secretData = new LinkedHashMap<>();
        boolean[] changed = {false};

        data.replaceAll((group, json) -> {
            if (json == null || json.isBlank()) return json;
            try {
                var node = mapper.readTree(json);
                if (!node.isObject()) return json;
                var mutable = (com.fasterxml.jackson.databind.node.ObjectNode) node.deepCopy();
                boolean groupChanged = false;
                for (String field : API_KEY_FIELDS) {
                    var val = mutable.get(field);
                    if (val == null || val.isNull()) continue;
                    mutable.remove(field);
                    groupChanged = true;
                    changed[0] = true;
                    if (!val.asText("").isBlank()) {
                        secretData.put(field, val.asText());
                    }
                }
                return groupChanged ? mapper.writeValueAsString(mutable) : json;
            } catch (Exception e) {
                log.debug("[ApiKeyMigration] 跳过无法解析的配置分组 {}: {}", group, e.getMessage());
                return json;
            }
        });

        if (!changed[0]) {
            log.info("[ApiKeyMigration] ConfigMap 中无 API Key，跳过");
            return Mono.empty();
        }

        cm.setData(data);
        Mono<Void> updateConfig = extensionClient.update(cm).then();
        Mono<Void> updateSecret = secretData.isEmpty()
            ? Mono.empty()
            : upsertSecret(secretData);

        return updateConfig
            .then(updateSecret)
            .doOnSuccess(ignored -> log.info("[ApiKeyMigration] 成功迁移 {} 个 API Key",
                secretData.size()));
    }

    private Mono<Void> upsertSecret(Map<String, String> secretData) {
        return extensionClient.fetch(Secret.class, SECRET_NAME)
            .flatMap(existing -> {
                Map<String, String> merged = new LinkedHashMap<>();
                if (existing.getStringData() != null) {
                    merged.putAll(existing.getStringData());
                }
                merged.putAll(secretData);
                existing.setStringData(merged);
                return extensionClient.update(existing).then();
            })
            .switchIfEmpty(Mono.defer(() -> {
                Secret secret = new Secret();
                secret.setMetadata(new Metadata());
                secret.getMetadata().setName(SECRET_NAME);
                secret.setStringData(secretData);
                secret.setType("Opaque");
                return extensionClient.create(secret).then();
            }));
    }
}

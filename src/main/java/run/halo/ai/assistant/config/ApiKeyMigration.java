package run.halo.ai.assistant.config;

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

    private static final String CONFIG_MAP_NAME = "ai-assistant-configmap";
    private static final String SECRET_NAME = "ai-assistant-api-keys";
    private static final String[] API_KEY_FIELDS = {
        "chatApiKey", "embeddingApiKey", "rerankApiKey", "queryRewriteApiKey"
    };

    private final ReactiveExtensionClient extensionClient;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @PostConstruct
    void migrate() {
        // 延迟 5 秒，确保 Halo 扩展体系完全就绪
        Mono.delay(Duration.ofSeconds(5))
            .flatMap(ignored -> extensionClient.fetch(Secret.class, SECRET_NAME)
                .hasElement()
                .flatMap(secretExists -> {
                    if (secretExists) {
                        log.info("[ApiKeyMigration] Secret 已存在，跳过");
                        return Mono.empty();
                    }
                    return extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                        .flatMap(cm -> {
                            var data = cm.getData();
                            if (data == null) return Mono.empty();
                            String modelsJson = data.get("models");
                            if (modelsJson == null || modelsJson.isBlank()) return Mono.empty();

                            try {
                                var node = mapper.readTree(modelsJson);
                                if (!node.isObject()) return Mono.empty();

                                boolean hasKeys = false;
                                var mutable = (com.fasterxml.jackson.databind.node.ObjectNode) node.deepCopy();
                                Map<String, String> secretData = new LinkedHashMap<>();
                                for (String field : API_KEY_FIELDS) {
                                    var val = mutable.get(field);
                                    if (val != null && !val.isNull() && !val.asText().isBlank()) {
                                        secretData.put(field, val.asText());
                                        mutable.remove(field);
                                        hasKeys = true;
                                    }
                                }

                                if (!hasKeys) {
                                    log.info("[ApiKeyMigration] ConfigMap 中无 API Key，跳过");
                                    return Mono.empty();
                                }

                                data.put("models", mapper.writeValueAsString(mutable));
                                cm.setData(data);

                                Secret secret = new Secret();
                                secret.setMetadata(new Metadata());
                                secret.getMetadata().setName(SECRET_NAME);
                                secret.setStringData(secretData);
                                secret.setType("Opaque");

                                return extensionClient.update(cm)
                                    .then(extensionClient.create(secret))
                                    .doOnSuccess(s -> log.info("[ApiKeyMigration] 成功迁移 {} 个 API Key", secretData.size()));
                            } catch (Exception e) {
                                log.warn("[ApiKeyMigration] 迁移失败: {}", e.getMessage());
                                return Mono.empty();
                            }
                        });
                })
            )
            .onErrorResume(e -> {
                log.warn("[ApiKeyMigration] 异常: {}", e.getMessage());
                return Mono.empty();
            })
            .subscribe();
    }
}

package run.halo.ai.assistant.endpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.llm.LlmClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console 管理端配置 API — 需要管理员权限
 *
 * 组前缀用 "console.api." 开头，Halo 会要求管理员登录
 * 完整 URL: /apis/console.api.ai-assistant.halo.run/v1alpha1/config
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleConfigEndpoint implements CustomEndpoint {

    private final AIProperties aiProperties;
    private final LlmClient llmClient;
    private final ReactiveExtensionClient extensionClient;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
            .GET("/config", this::getConfig)
            .POST("/config/save", this::saveConfig)
            .POST("/config/test-connection", this::testConnection)
            .POST("/config/test-model", this::testModelWithBody)
            .POST("/config/test-embedding", this::testEmbedding)
            .POST("/config/test-rerank", this::testRerank)
            .POST("/config/test-query-rewrite", this::testQueryRewrite)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-assistant.halo.run", "v1alpha1");
    }

    /**
     * 保存配置 — 前端提交各 group 的 JSON，写入 ConfigMap
     * 请求体: { "models": "{...json...}", "chunking": "{...}", ... }
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> saveConfig(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                Map<String, String> data = new LinkedHashMap<>();
                for (Object key : body.keySet()) {
                    Object val = body.get(key);
                    // 值可能是 String（JSON 字符串）或 Map（自动序列化）
                    if (val instanceof String s) {
                        data.put(key.toString(), s);
                    } else if (val != null) {
                        try {
                            data.put(key.toString(),
                                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(val));
                        } catch (Exception e) {
                            data.put(key.toString(), "{}");
                        }
                    }
                }

                String configMapName = "ai-assistant-configmap";
                return extensionClient.fetch(ConfigMap.class, configMapName)
                    .flatMap(existing -> {
                        existing.setData(data);
                        return extensionClient.update(existing);
                    })
                    .onErrorResume(e -> {
                        // ConfigMap 不存在，创建新的
                        ConfigMap newCm = new ConfigMap();
                        newCm.getMetadata().setName(configMapName);
                        newCm.setData(data);
                        return extensionClient.create(newCm);
                    });
            })
            .flatMap(saved -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("saved", true)))
            .onErrorResume(e -> {
                log.error("[ConsoleConfigEndpoint] 保存配置失败", e);
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("saved", false, "error", e.getMessage()));
            });
    }

    private Mono<ServerResponse> getConfig(ServerRequest request) {
        return Mono.zip(
            aiProperties.getModelConfig(),
            aiProperties.getChunkConfig(),
            aiProperties.getRetrievalConfig(),
            aiProperties.getEnhancementConfig(),
            aiProperties.getChatConfig()
        ).flatMap(tuple -> ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "models", tuple.getT1(),
                "chunking", tuple.getT2(),
                "retrieval", tuple.getT3(),
                "enhancement", tuple.getT4(),
                "chat", tuple.getT5()
            ))
        );
    }

    /**
     * 真正的连通性测试 — 发一条简短消息给 LLM，验证 baseUrl / apiKey / model 是否正确
     *
     * 成功返回 { connected: true, model, reply }
     * 失败返回 { connected: false, error: "具体错误原因" }
     */
    private Mono<ServerResponse> testConnection(ServerRequest request) {
        return aiProperties.getModelConfig()
            .flatMap(modelConfig -> {
                // 用最少 token 的测试消息
                List<Map<String, String>> messages = List.of(
                    Map.of("role", "user", "content", "Hi")
                );

                return llmClient.chat(
                    modelConfig.getChatBaseUrl(),
                    modelConfig.getChatApiKey(),
                    modelConfig.getChatModel(),
                    messages,
                    0.0f,   // temperature=0 保证稳定输出
                    32      // maxTokens=32 节省费用
                ).flatMap(reply -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "connected", true,
                        "model", modelConfig.getChatModel(),
                        "reply", reply
                    ))
                );
            })
            .onErrorResume(e -> {
                // 捕获所有错误（网络、认证、模型不存在等），返回友好的错误信息
                log.warn("[ConsoleConfigEndpoint] 连通性测试失败: {}", e.getMessage());
                String errorMsg = extractErrorMessage(e);
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "connected", false,
                        "error", errorMsg
                    ));
            });
    }

    /**
     * 从异常中提取人类可读的错误信息
     * 优先提取 WebClientResponseException 的原始响应体（包含 API 返回的具体错误）
     */
    private String extractErrorMessage(Throwable e) {
        // WebClient 的 HTTP 错误，提取 API 返回的原始错误信息
        if (e instanceof WebClientResponseException wce) {
            int status = wce.getStatusCode().value();
            String body = wce.getResponseBodyAsString();
            log.warn("[ConsoleConfigEndpoint] API 返回 HTTP {}: {}", status, body);

            // 尝试从 JSON 响应中提取 error.message
            String apiError = parseApiErrorMessage(body);
            if (apiError != null && !apiError.isBlank()) {
                return apiError;
            }

            // 兜底：按状态码给提示
            return switch (status) {
                case 401 -> "API Key 无效或已过期";
                case 403 -> "无权访问该模型，请检查 API Key 权限";
                case 404 -> "API 地址或模型名称错误，请检查 Base URL 和模型名";
                case 429 -> "API 调用频率超限，请稍后重试";
                default -> "HTTP " + status + ": " + (body.length() > 200 ? body.substring(0, 200) : body);
            };
        }

        // 非 HTTP 错误（网络、DNS 等）
        String msg = e.getMessage();
        if (msg == null) return "未知错误";
        if (msg.contains("Connection refused") || msg.contains("connect timed out")) {
            return "无法连接到 API 服务器，请检查网络和 Base URL";
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    /**
     * 从 API 返回的 JSON 错误响应中提取 error.message 字段
     * 兼容 OpenAI 格式 { "error": { "message": "..." } } 和简单格式
     */
    private String parseApiErrorMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            // OpenAI 格式: { "error": { "message": "xxx" } }
            var errorMsg = node.path("error").path("message").asText(null);
            if (errorMsg != null && !errorMsg.isBlank()) return errorMsg;
            // 百度格式: { "error_msg": "xxx" }
            errorMsg = node.path("error_msg").asText(null);
            if (errorMsg != null && !errorMsg.isBlank()) return errorMsg;
            // 简单格式: { "message": "xxx" }
            errorMsg = node.path("message").asText(null);
            return (errorMsg != null && !errorMsg.isBlank()) ? errorMsg : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 用请求体中的参数测试模型连通性（支持未保存的配置）
     *
     * 请求体: { "baseUrl": "...", "apiKey": "...", "model": "..." }
     * 任意字段为空时，从已保存的配置中回退读取
     */
    private Mono<ServerResponse> testModelWithBody(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String baseUrl = (String) body.getOrDefault("baseUrl", "");
                String apiKey = (String) body.getOrDefault("apiKey", "");
                String model = (String) body.getOrDefault("model", "");

                // 如果请求体参数不全，从已保存配置中补全
                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        String finalBaseUrl = (baseUrl == null || baseUrl.isBlank()) ? saved.getChatBaseUrl() : baseUrl;
                        String finalApiKey = (apiKey == null || apiKey.isBlank()) ? saved.getChatApiKey() : apiKey;
                        String finalModel = (model == null || model.isBlank()) ? saved.getChatModel() : model;

                        if (finalBaseUrl.isBlank() || finalApiKey.isBlank() || finalModel.isBlank()) {
                            return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", false,
                                    "error", "缺少必要参数：Base URL、API Key、模型名称不能为空"
                                ));
                        }

                        List<Map<String, String>> messages = List.of(
                            Map.of("role", "user", "content", "Hi")
                        );
                        return llmClient.chat(finalBaseUrl, finalApiKey, finalModel, messages, 0.0f, 32)
                            .flatMap(reply -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", true,
                                    "model", finalModel,
                                    "reply", reply
                                ))
                            );
                    });
            })
            .onErrorResume(e -> {
                log.warn("[ConsoleConfigEndpoint] 模型连通性测试失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "connected", false,
                        "error", extractErrorMessage(e)
                    ));
            });
    }

    /**
     * 测试 Embedding 模型连通性
     *
     * 请求体: { "baseUrl": "...", "apiKey": "...", "model": "...", "dimensions": 1024 }
     * 任意字段为空时，从已保存的配置中回退读取
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> testEmbedding(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String baseUrl = (String) body.getOrDefault("baseUrl", "");
                String apiKey = (String) body.getOrDefault("apiKey", "");
                String model = (String) body.getOrDefault("model", "");
                Object dimObj = body.get("dimensions");
                final int dimensions;
                if (dimObj instanceof Number n) {
                    dimensions = n.intValue();
                } else {
                    dimensions = 0;
                }

                // 从已保存配置中补全空字段
                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        String finalBaseUrl = (baseUrl == null || baseUrl.isBlank()) ? saved.getEmbeddingBaseUrl() : baseUrl;
                        String finalApiKey = (apiKey == null || apiKey.isBlank()) ? saved.getEmbeddingApiKey() : apiKey;
                        String finalModel = (model == null || model.isBlank()) ? saved.getEmbeddingModel() : model;
                        int finalDimensions = dimensions > 0 ? dimensions : saved.getEmbeddingDimensions();

                        if (finalBaseUrl.isBlank() || finalApiKey.isBlank() || finalModel.isBlank()) {
                            return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", false,
                                    "error", "缺少必要参数：Base URL、API Key、模型名称不能为空"
                                ));
                        }

                        return llmClient.embed(finalBaseUrl, finalApiKey, finalModel, "Hello", finalDimensions)
                            .flatMap(vector -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", true,
                                    "model", finalModel,
                                    "dimensions", vector.length,
                                    "requestedDimensions", finalDimensions
                                ))
                            );
                    });
            })
            .onErrorResume(e -> {
                log.warn("[ConsoleConfigEndpoint] Embedding 连通性测试失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "connected", false,
                        "error", extractErrorMessage(e)
                    ));
            });
    }

    /**
     * 测试 Rerank 模型连通性
     *
     * 请求体: { "baseUrl": "...", "apiKey": "...", "model": "..." }
     * 对一条 query + 一条文档做 rerank，验证服务是否正常
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> testRerank(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String baseUrl = (String) body.getOrDefault("baseUrl", "");
                String apiKey = (String) body.getOrDefault("apiKey", "");
                String model = (String) body.getOrDefault("model", "");

                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        String finalBaseUrl = isBlank(baseUrl) ? saved.getRerankBaseUrl() : baseUrl;
                        String finalApiKey = isBlank(apiKey) ? saved.getRerankApiKey() : apiKey;
                        String finalModel = isBlank(model) ? saved.getRerankModel() : model;

                        if (finalBaseUrl.isBlank() || finalApiKey.isBlank() || finalModel.isBlank()) {
                            return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", false,
                                    "error", "缺少必要参数：Base URL、API Key、模型名称不能为空"
                                ));
                        }

                        return llmClient.rerank(finalBaseUrl, finalApiKey, finalModel,
                                "什么是机器学习", List.of("机器学习是人工智能的一个分支。"), 1)
                            .flatMap(results -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", true,
                                    "model", finalModel,
                                    "relevanceScore", results.isEmpty() ? 0 : results.get(0).relevanceScore()
                                ))
                            );
                    });
            })
            .onErrorResume(e -> {
                log.warn("[ConsoleConfigEndpoint] Rerank 连通性测试失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "connected", false,
                        "error", extractErrorMessage(e)
                    ));
            });
    }

    /**
     * 测试查询改写模型连通性
     *
     * 请求体: { "baseUrl": "...", "apiKey": "...", "model": "..." }
     * 查询改写本质是 Chat 调用，用一条简短消息验证
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> testQueryRewrite(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String baseUrl = (String) body.getOrDefault("baseUrl", "");
                String apiKey = (String) body.getOrDefault("apiKey", "");
                String model = (String) body.getOrDefault("model", "");

                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        // 查询改写的 URL/Key/Model 留空时复用对话模型配置
                        String finalBaseUrl = isBlank(baseUrl) ? (isBlank(saved.getQueryRewriteBaseUrl()) ? saved.getChatBaseUrl() : saved.getQueryRewriteBaseUrl()) : baseUrl;
                        String finalApiKey = isBlank(apiKey) ? (isBlank(saved.getQueryRewriteApiKey()) ? saved.getChatApiKey() : saved.getQueryRewriteApiKey()) : apiKey;
                        String finalModel = isBlank(model) ? (isBlank(saved.getQueryRewriteModel()) ? saved.getChatModel() : saved.getQueryRewriteModel()) : model;

                        if (finalBaseUrl.isBlank() || finalApiKey.isBlank() || finalModel.isBlank()) {
                            return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", false,
                                    "error", "缺少必要参数：Base URL、API Key、模型名称不能为空"
                                ));
                        }

                        List<Map<String, String>> messages = List.of(
                            Map.of("role", "user", "content", "Hi")
                        );
                        return llmClient.chat(finalBaseUrl, finalApiKey, finalModel, messages, 0.0f, 32)
                            .flatMap(reply -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of(
                                    "connected", true,
                                    "model", finalModel,
                                    "reply", reply
                                ))
                            );
                    });
            })
            .onErrorResume(e -> {
                log.warn("[ConsoleConfigEndpoint] 查询改写连通性测试失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "connected", false,
                        "error", extractErrorMessage(e)
                    ));
            });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

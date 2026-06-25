package run.halo.ai.suite.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;
import run.halo.ai.suite.rag.PipelineTrace;
import run.halo.ai.suite.service.ChatService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Console 管理端配置 API — 需要管理员权限
 *
 * 组前缀用 "console.api." 开头，Halo 会要求管理员登录
 * 完整 URL: /apis/console.api.ai-suite.halo.run/v1alpha1/config
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleConfigEndpoint implements CustomEndpoint {

    private static final String CONFIG_MAP_NAME = "ai-suite-configmap";
    private static final String LEGACY_CONFIG_MAP_NAME = "ai-assistant-configmap";
    private static final Set<String> MODEL_FIELDS = Set.of(
        "aiFoundationChatModelName",
        "aiFoundationEmbeddingModelName",
        "aiFoundationRerankModelName",
        "aiFoundationQueryRewriteModelName",
        "embeddingDimensions",
        "rerankEnabled",
        "queryRewriteEnabled"
    );
    private static final Set<String> WRITING_FIELDS = Set.of(
        "enabled",
        "writingModel",
        "outlineTemperature",
        "outlineSections",
        "outlineDepth",
        "outlineNumbering",
        "outlineExtraPrompt",
        "maxInputLength",
        "maxTokens"
    );

    private final AIProperties aiProperties;
    private final LlmClient llmClient;
    private final ReactiveExtensionClient extensionClient;
    private final ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            .POST("/chat/debug/stream", this::handleDebugStreamChat)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    /**
     * 保存配置 — AI Suite 只保存业务配置，模型凭据由 AI Foundation 管理。
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> saveConfig(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                Map<String, String> configData = new LinkedHashMap<>();
                for (Object key : body.keySet()) {
                    Object val = body.get(key);
                    String json;
                    if (val instanceof String s) {
                        json = s;
                    } else if (val != null) {
                        try {
                            json = objectMapper.writeValueAsString(val);
                        } catch (Exception e) {
                            json = "{}";
                        }
                    } else {
                        continue;
                    }

                    try {
                        JsonNode node = objectMapper.readTree(json);
                        if (node.isObject()) {
                            ObjectNode mutable = node.deepCopy();
                            if ("models".equals(key.toString())) {
                                mutable.retain(MODEL_FIELDS);
                            } else if ("writing".equals(key.toString())) {
                                mutable.retain(WRITING_FIELDS);
                            }
                            json = objectMapper.writeValueAsString(mutable);
                        }
                    } catch (Exception ignored) {}

                    configData.put(key.toString(), json);
                }

                Mono<ConfigMap> saveConfig = extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                    .flatMap(existing -> {
                        // 保留 non-sensitive 的旧值（如果前端没传）
                        if (existing.getData() != null) {
                            for (var entry : existing.getData().entrySet()) {
                                configData.putIfAbsent(entry.getKey(), entry.getValue());
                            }
                        }
                        existing.setData(configData);
                        return extensionClient.update(existing);
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        ConfigMap newCm = new ConfigMap();
                        newCm.setMetadata(new Metadata());
                        newCm.getMetadata().setName(CONFIG_MAP_NAME);
                        return extensionClient.fetch(ConfigMap.class, LEGACY_CONFIG_MAP_NAME)
                            .map(legacy -> {
                                Map<String, String> merged = new LinkedHashMap<>();
                                if (legacy.getData() != null) merged.putAll(legacy.getData());
                                merged.putAll(configData);
                                return merged;
                            })
                            .defaultIfEmpty(configData)
                            .flatMap(data -> {
                                newCm.setData(data);
                                return extensionClient.create(newCm);
                            });
                    }));

                return saveConfig
                    .then(ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("saved", true)));
            })
            .onErrorResume(e -> {
                log.error("[ConsoleConfigEndpoint] 保存配置失败", e);
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("saved", false, "error", e.getMessage()));
            });
    }

    private Mono<ServerResponse> getConfig(ServerRequest request) {
        return Mono.zip(
            Mono.zip(
            aiProperties.getModelConfig(),
            aiProperties.getChunkConfig(),
            aiProperties.getRetrievalConfig(),
            aiProperties.getEnhancementConfig(),
            aiProperties.getChatConfig(),
            readExcerptConfig(),
            aiProperties.getWritingConfig(),
            aiProperties.getSearchConfig()
            ),
            aiProperties.getMindMapConfig()
        ).flatMap(tuple -> {
            var base = tuple.getT1();
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "models", base.getT1(),
                    "chunking", base.getT2(),
                    "retrieval", base.getT3(),
                    "enhancement", base.getT4(),
                    "chat", base.getT5(),
                    "excerpt", base.getT6(),
                    "writing", base.getT7(),
                    "search", base.getT8(),
                    "mindmap", tuple.getT2()
                ));
        });
    }

    private Mono<Map<String, Object>> readExcerptConfig() {
        return fetchConfigMapWithLegacyFallback()
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String json = data.get("excerpt");
                if (json == null || json.isBlank()) return Map.<String, Object>of();
                try {
                    JsonNode node = new ObjectMapper().readTree(json);
                    // 把整棵 JSON 节点原样转 Map，前端能拿到所有字段（含 maxLength/maxInputLength/prompt）
                    return new ObjectMapper().convertValue(
                        node, new com.fasterxml.jackson.core.type.TypeReference
                            <Map<String, Object>>() {});
                } catch (Exception e) {
                    return Map.<String, Object>of();
                }
            })
            .defaultIfEmpty(Map.of());
    }

    private Mono<ConfigMap> fetchConfigMapWithLegacyFallback() {
        return extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .switchIfEmpty(extensionClient.fetch(ConfigMap.class, LEGACY_CONFIG_MAP_NAME));
    }

    /**
     * 真正的连通性测试 — 发一条简短消息给 AI Foundation 语言模型。
     */
    private Mono<ServerResponse> testConnection(ServerRequest request) {
        return aiProperties.getModelConfig()
            .flatMap(modelConfig -> {
                List<Map<String, String>> messages = List.of(
                    Map.of("role", "user", "content", "Hi")
                );

                return llmClient.chat(
                    modelConfig.getEffectiveChatModel(),
                    messages,
                    0.0f,
                    32,
                    null,
                    null,
                    UsageScenario.MODEL_TEST
                ).flatMap(reply -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                        "connected", true,
                        "model", modelConfig.getEffectiveChatModel(),
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

    private String extractErrorMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return "未知错误";
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    /**
     * 用请求体中的 AI Foundation 模型资源名测试语言模型。
     */
    private Mono<ServerResponse> testModelWithBody(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String model = (String) body.getOrDefault("model", "");

                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        String finalModel = (model == null || model.isBlank()) ? saved.getEffectiveChatModel() : model;

                        List<Map<String, String>> messages = List.of(
                            Map.of("role", "user", "content", "你好，请用一句话回复：模型连接测试成功")
                        );
                        return llmClient.chatStream(finalModel, messages, 0.0f, 128,
                                null, null, UsageScenario.MODEL_TEST)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
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
     * 测试 AI Foundation Embedding 模型连通性。
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> testEmbedding(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String model = (String) body.getOrDefault("model", "");
                Object dimObj = body.get("dimensions");
                final int dimensions;
                if (dimObj instanceof Number n) {
                    dimensions = n.intValue();
                } else {
                    dimensions = 0;
                }

                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        String finalModel = (model == null || model.isBlank()) ? saved.getEffectiveEmbeddingModel() : model;
                        int finalDimensions = dimensions > 0 ? dimensions : saved.getEmbeddingDimensions();

                        return llmClient.embed(finalModel, "Hello", finalDimensions,
                                UsageScenario.MODEL_TEST)
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
     * 测试 AI Foundation Rerank 模型连通性。
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> testRerank(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String model = (String) body.getOrDefault("model", "");

                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        String finalModel = isBlank(model) ? saved.getEffectiveRerankModel() : model;

                        return llmClient.rerank(finalModel,
                                "什么是机器学习", List.of("机器学习是人工智能的一个分支。"), 1,
                                UsageScenario.MODEL_TEST)
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
     * 测试查询改写模型连通性。
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> testQueryRewrite(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String model = (String) body.getOrDefault("model", "");

                return aiProperties.getModelConfig()
                    .flatMap(saved -> {
                        String finalModel = isBlank(model) ? (isBlank(saved.getEffectiveQueryRewriteModel()) ? saved.getEffectiveChatModel() : saved.getEffectiveQueryRewriteModel()) : model;

                        List<Map<String, String>> messages = List.of(
                            Map.of("role", "user", "content", "你好，请用一句话回复：查询改写模型连接成功")
                        );
                        return llmClient.chatStream(finalModel, messages, 0.0f, 128,
                                null, null, UsageScenario.MODEL_TEST)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
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

    // ===== 调试流式对话 SSE =====

    /**
     * 调试模式流式对话 — 推送管线追踪 + token 流
     *
     * SSE 事件顺序：
     * 1. event: trace_stage（每阶段一条）
     * 2. event: citations
     * 3. data: {"content":"token"}（LLM token 流）
     * 4. event: trace_summary
     * 5. data: [DONE]
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> handleDebugStreamChat(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String message = (String) body.getOrDefault("message", "");
                List<Map<String, String>> history = parseHistory(body.get("history"));

                if (message.isBlank()) {
                    return ServerResponse.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", "message 不能为空"));
                }

                return chatService.chatStreamWithDebug(message, history, null)
                    .flatMap(debugResp -> {
                        PipelineTrace trace = debugResp.trace();

                        // 1. 追踪阶段事件
                        Flux<ServerSentEvent<String>> traceFrames = Flux.fromIterable(trace.stages())
                            .map(stage -> ServerSentEvent.<String>builder()
                                .event("trace_stage")
                                .data(toJson(stage))
                                .build());

                        // 2. 引用事件
                        Flux<ServerSentEvent<String>> citationFrame = Flux.empty();
                        if (debugResp.citations() != null && !debugResp.citations().isEmpty()) {
                            citationFrame = Flux.just(
                                ServerSentEvent.<String>builder()
                                    .event("citations")
                                    .data(toJson(debugResp.citations()))
                                    .build()
                            );
                        }

                        // 3. Token 流
                        Flux<ServerSentEvent<String>> tokenFrames = debugResp.stream()
                            .filter(token -> token != null && !token.isEmpty())
                            .map(token -> ServerSentEvent.<String>builder()
                                .data(wrapToken(token))
                                .build());

                        // 4. 追踪汇总
                        Flux<ServerSentEvent<String>> summaryFrame = Flux.just(
                            ServerSentEvent.<String>builder()
                                .event("trace_summary")
                                .data(toJson(Map.of(
                                    "totalMs", trace.totalDurationMs(),
                                    "stageCount", trace.stages().size(),
                                    "intent", trace.intent()
                                )))
                                .build()
                        );

                        // 5. 结束标记
                        Flux<ServerSentEvent<String>> doneFrame = Flux.just(
                            ServerSentEvent.<String>builder()
                                .data("[DONE]")
                                .build()
                        );

                        return ServerResponse.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body(
                                traceFrames
                                    .concatWith(citationFrame)
                                    .concatWith(tokenFrames)
                                    .concatWith(summaryFrame)
                                    .concatWith(doneFrame),
                                ServerSentEvent.class
                            );
                    });
            })
            .onErrorResume(e -> {
                log.error("[ConsoleConfigEndpoint] 调试对话失败", e);
                return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(
                        Flux.just(
                            ServerSentEvent.<String>builder()
                                .data(wrapToken("[AI 服务异常，已中断]"))
                                .build(),
                            ServerSentEvent.<String>builder()
                                .data("[DONE]")
                                .build()
                        ),
                        ServerSentEvent.class
                    );
            });
    }

    /** JSON 序列化 */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 包装 token 为 JSON */
    private String wrapToken(String token) {
        try {
            return objectMapper.writeValueAsString(Map.of("content", token));
        } catch (Exception e) {
            return "{\"content\":\"\"}";
        }
    }

    /** 解析对话历史 */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseHistory(Object historyObj) {
        if (historyObj instanceof List<?> list) {
            return (List<Map<String, String>>) list;
        }
        return List.of();
    }
}

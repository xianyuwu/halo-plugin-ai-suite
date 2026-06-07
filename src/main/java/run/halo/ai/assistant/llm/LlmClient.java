package run.halo.ai.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.endpoint.UsageLimit.LimitExceededException;
import run.halo.ai.assistant.state.LimitGuard;
import run.halo.ai.assistant.state.UsageTracker;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 统一 LLM HTTP 客户端 — 仅支持 OpenAI 兼容协议
 *
 * 国内主流厂商（DeepSeek、阿里云、智谱、Moonshot 等）都支持 OpenAI 兼容接口。
 */
@Slf4j
@Component
public class LlmClient {

    // 插件子容器没有 ObjectMapper Bean，本地创建
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AIProperties aiProperties;

    // 用 @Lazy 打破 LlmClient ↔ UsageTracker / LimitGuard 之间的潜在循环
    // (LlmClient 被 service 注,UsageTracker 仅被 LlmClient 注,不会真循环,但 @Lazy 保险)
    private final UsageTracker usageTracker;
    private final LimitGuard limitGuard;

    public LlmClient(AIProperties aiProperties,
                     @Lazy UsageTracker usageTracker,
                     @Lazy LimitGuard limitGuard) {
        this.aiProperties = aiProperties;
        this.usageTracker = usageTracker;
        this.limitGuard = limitGuard;
    }

    // ===== URL 修正 =====

    /**
     * 确保 baseUrl 以 /v1 结尾（或包含版本路径）
     * 用户可能只填 https://api.deepseek.com 而漏掉 /v1
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
        // 已经包含版本路径的不处理
        if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")
            || baseUrl.contains("/v1/") || baseUrl.contains("/v2/")
            || baseUrl.contains("/v3/") || baseUrl.contains("/v4/")
            || baseUrl.contains("/paas/")) {
            return baseUrl;
        }
        // 去掉末尾斜杠后追加 /v1
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        log.info("[LlmClient] baseUrl 未包含版本路径，自动追加 /v1: {} → {}", baseUrl, trimmed + "/v1");
        return trimmed + "/v1";
    }

    // ===== Chat Completion =====

    public Mono<String> chat(String baseUrl, String apiKey, String model,
                             List<Map<String, String>> messages,
                             float temperature, int maxTokens) {
        return chat(baseUrl, apiKey, model, messages, temperature, maxTokens, null, null);
    }

    /** 带 responseFormat 但无访客 IP 的重载 — 后台写作/摘要等管理端调用用(不限流访客) */
    public Mono<String> chat(String baseUrl, String apiKey, String model,
                             List<Map<String, String>> messages,
                             float temperature, int maxTokens,
                             Map<String, Object> responseFormat) {
        return chat(baseUrl, apiKey, model, messages, temperature, maxTokens, responseFormat, null);
    }

    public Mono<String> chat(String baseUrl, String apiKey, String model,
                             List<Map<String, String>> messages,
                             float temperature, int maxTokens,
                             Map<String, Object> responseFormat, String clientIp) {
        return enforceLimit(model, "chat", clientIp)
            .then(Mono.defer(() -> {
                String url = normalizeBaseUrl(baseUrl);
                String bodyStr = buildOpenAiChatBody(model, messages, temperature, maxTokens, false, responseFormat).toString();

                return WebClient.create()
                    .post()
                    .uri(url + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(bodyStr)
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractOpenAiContent)
                    .doOnSuccess(r -> {
                        // 非流式: 没法拿 prompt token, 只统计 completion 估算; prompt=0
                        usageTracker.recordUsage(model, "chat", 0, estimateTokens(r), false, 0);
                    })
                    .doOnError(e -> usageTracker.recordUsage(model, "chat", 0, 0, true, 0));
            }));
    }

    public Flux<String> chatStream(String baseUrl, String apiKey, String model,
                                    List<Map<String, String>> messages,
                                    float temperature, int maxTokens) {
        return chatStream(baseUrl, apiKey, model, messages, temperature, maxTokens, null, null);
    }

    /** 带 responseFormat 但无访客 IP 的重载 — 后台写作流式调用用(不限流访客) */
    public Flux<String> chatStream(String baseUrl, String apiKey, String model,
                                    List<Map<String, String>> messages,
                                    float temperature, int maxTokens,
                                    Map<String, Object> responseFormat) {
        return chatStream(baseUrl, apiKey, model, messages, temperature, maxTokens, responseFormat, null);
    }

    public Flux<String> chatStream(String baseUrl, String apiKey, String model,
                                    List<Map<String, String>> messages,
                                    float temperature, int maxTokens,
                                    Map<String, Object> responseFormat, String clientIp) {
        // 限流是同步检查;这里强制 .block() — 配置读一次 IO,可接受
        // chatStream 是 Flux, 没法用 .then(Mono.defer) 包裹,直接在调用方上游 service 之前
        // 串一个 .flatMap 触发 (下面用 Mono.defer 包装, Flux.from(Mono) 转换)
        // 为了不破坏公开签名,采用在订阅时才检查的策略:用 Flux.defer 包裹

        // 实际实现:把限流检查塞进 Flux.defer 内部,订阅时才执行
        String url = normalizeBaseUrl(baseUrl);
        // 加 stream_options 让 OpenAI 兼容厂商在末帧返回 usage
        ObjectNode body = buildOpenAiChatBody(model, messages, temperature, maxTokens, true, responseFormat);
        body.putPOJO("stream_options", Map.of("include_usage", true));
        String bodyStr = body.toString();

        StringBuilder buf = new StringBuilder();
        StringBuilder contentBuf = new StringBuilder();
        long[] usage = new long[]{0L, 0L}; // [promptTokens, completionTokens]

        return Flux.defer(() -> enforceLimit(model, "chat", clientIp)
            .thenMany(WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build()
                .post()
                .uri(url + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bodyStr)
                .retrieve()
                .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                .map(db -> {
                    byte[] bytes = new byte[db.readableByteCount()];
                    db.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(db);
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                })
                .concatMap(chunk -> {
                    buf.append(chunk);
                    List<String> lines = new java.util.ArrayList<>();
                    int idx;
                    while ((idx = buf.indexOf("\n")) >= 0) {
                        lines.add(buf.substring(0, idx));
                        buf.delete(0, idx + 1);
                    }
                    return Flux.fromIterable(lines);
                })
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .filter(line -> !line.isEmpty() && !"[DONE]".equals(line))
                .map(this::extractOpenAiStreamContentFromJson)
                .doOnNext(token -> {
                    if (!token.isEmpty()) contentBuf.append(token);
                })
                .doOnComplete(() -> {
                    // 尝试从完整 SSE 缓冲里捞 usage 字段(末帧或在最后一个有 choices:[] 但 usage 非空的帧)
                    extractUsageFromBuffer(buf.toString(), usage);
                    if (usage[0] == 0 && usage[1] == 0) {
                        // 没拿到, 用字符估算
                        long estCompletion = estimateTokens(contentBuf.toString());
                        usage[1] = estCompletion;
                    }
                    usageTracker.recordUsage(model, "chat", usage[0], usage[1], false, 0);
                })
                .doOnError(e -> usageTracker.recordUsage(model, "chat", 0, 0, true, 0))));
    }

    // ===== 内部调用（跳过限流） — 后台关键词提取/写作/摘要等用 =====

    /** 内部 chat — 不限流、不限访客 IP */
    public Mono<String> chatInternal(String baseUrl, String apiKey, String model,
                                     List<Map<String, String>> messages,
                                     float temperature, int maxTokens) {
        return chatInternal(baseUrl, apiKey, model, messages, temperature, maxTokens, null);
    }

    /** 内部 chat 带 responseFormat — 不限流 */
    public Mono<String> chatInternal(String baseUrl, String apiKey, String model,
                                     List<Map<String, String>> messages,
                                     float temperature, int maxTokens,
                                     Map<String, Object> responseFormat) {
        String url = normalizeBaseUrl(baseUrl);
        String bodyStr = buildOpenAiChatBody(model, messages, temperature, maxTokens, false, responseFormat).toString();

        return WebClient.create()
            .post()
            .uri(url + "/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(bodyStr)
            .retrieve()
            .bodyToMono(String.class)
            .doOnNext(r -> {
                long outputTokens = estimateTokens(extractOpenAiContent(r));
                usageTracker.recordUsage(model, "chat", 0, outputTokens, false, 0);
            })
            .map(this::extractOpenAiContent)
            .doOnError(e -> usageTracker.recordUsage(model, "chat", 0, 0, true, 0));
    }

    /** 内部 chatStream — 不限流 */
    public Flux<String> chatStreamInternal(String baseUrl, String apiKey, String model,
                                           List<Map<String, String>> messages,
                                           float temperature, int maxTokens,
                                           Map<String, Object> responseFormat) {
        String url = normalizeBaseUrl(baseUrl);
        ObjectNode body = buildOpenAiChatBody(model, messages, temperature, maxTokens, true, responseFormat);
        body.putPOJO("stream_options", Map.of("include_usage", true));
        String bodyStr = body.toString();

        StringBuilder buf = new StringBuilder();
        StringBuilder contentBuf = new StringBuilder();
        long[] usage = new long[]{0L, 0L};

        return WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build()
            .post()
            .uri(url + "/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(bodyStr)
            .retrieve()
            .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
            .map(db -> {
                byte[] bytes = new byte[db.readableByteCount()];
                db.read(bytes);
                org.springframework.core.io.buffer.DataBufferUtils.release(db);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            })
            .concatMap(chunk -> {
                buf.append(chunk);
                List<String> lines = new java.util.ArrayList<>();
                int idx;
                while ((idx = buf.indexOf("\n")) >= 0) {
                    lines.add(buf.substring(0, idx));
                    buf.delete(0, idx + 1);
                }
                return Flux.fromIterable(lines);
            })
            .map(String::trim)
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .filter(line -> !line.isEmpty() && !"[DONE]".equals(line))
            .map(this::extractOpenAiStreamContentFromJson)
            .doOnNext(token -> {
                if (!token.isEmpty()) contentBuf.append(token);
            })
            .doOnComplete(() -> {
                extractUsageFromBuffer(buf.toString(), usage);
                if (usage[0] == 0 && usage[1] == 0) {
                    long estCompletion = estimateTokens(contentBuf.toString());
                    usage[1] = estCompletion;
                }
                usageTracker.recordUsage(model, "chat", usage[0], usage[1], false, 0);
            })
            .doOnError(e -> usageTracker.recordUsage(model, "chat", 0, 0, true, 0));
    }

    /**
     * 限流检查 — 命中后抛 {@link LimitExceededException},由上游 service 的 onErrorResume 转错误响应.
     * 用 Mono.defer 让检查在订阅时才执行(避免 Flux 冷启动顺序问题).
     *
     * <p>clientIp 由 PublicChatEndpoint 从请求头提取后,通过方法参数一路透传到这里
     * (chat/chatStream → 这里),不走 reactor context: contextWrite 注入的 ctx 不会穿透到
     * ChatService 内部 chain (SSE body 的订阅链不在入口 contextWrite 的下游),实测会读到 null.
     * 嵌入/重排序场景传 null 即可(访客限流只对 chat 生效).
     */
    private Mono<Void> enforceLimit(String model, String type, String clientIp) {
        return Mono.defer(() -> limitGuard.check(model, type, clientIp)
            .flatMap(decision -> {
                if (!decision.allowed()) {
                    return Mono.error(new LimitExceededException(
                        decision.modelName(), decision.reason()));
                }
                return Mono.empty();
            }));
    }

    /** 从 OpenAI delta JSON（不带 "data: " 前缀）提取 content */
    private String extractOpenAiStreamContentFromJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    // ===== Embedding =====

    // Embedding 请求超时 — 单次最长等 30 秒，避免某个请求 hang 住拖死整个索引流程
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);

    public Mono<float[]> embed(String baseUrl, String apiKey, String model,
                                String text, int dimensions) {
        return enforceLimit(model, "embed", null)
            .then(Mono.defer(() -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("input", text);
                if (dimensions > 0) {
                    body.put("dimensions", dimensions);
                }

                return WebClient.create()
                    .post()
                    .uri(baseUrl + "/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(EMBEDDING_TIMEOUT)
                    .map(this::extractOpenAiEmbedding)
                    .doOnSuccess(v -> usageTracker.recordUsage(model, "embed", 0, 0, false, estimateTokens(text)))
                    .doOnError(e -> usageTracker.recordUsage(model, "embed", 0, 0, true, 0));
            }));
    }

    public Mono<List<float[]>> embedBatch(String baseUrl, String apiKey, String model,
                                           List<String> texts, int dimensions) {
        return enforceLimit(model, "embed", null)
            .then(Mono.defer(() -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                ArrayNode inputArray = body.putArray("input");
                texts.forEach(inputArray::add);
                if (dimensions > 0) {
                    body.put("dimensions", dimensions);
                }

                return WebClient.create()
                    .post()
                    .uri(baseUrl + "/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(EMBEDDING_TIMEOUT)
                    .map(this::extractOpenAiEmbeddings)
                    .doOnSuccess(v -> {
                        long total = texts.stream().mapToLong(this::estimateTokens).sum();
                        usageTracker.recordUsage(model, "embed", 0, 0, false, total);
                    })
                    .doOnError(e -> usageTracker.recordUsage(model, "embed", 0, 0, true, 0));
            }));
    }

    // ===== Rerank =====

    /**
     * Rerank 重排序 — 仅 OpenAI 兼容协议
     */
    public Mono<List<RerankResult>> rerank(String baseUrl, String apiKey, String model,
                                            String query, List<String> documents, int topN) {
        return enforceLimit(model, "rerank", null)
            .then(Mono.defer(() -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("query", query);
                body.put("top_n", topN);
                ArrayNode docsArray = body.putArray("documents");
                documents.forEach(docsArray::add);

                long tokens = estimateTokens(query)
                    + documents.stream().mapToLong(this::estimateTokens).sum();

                return WebClient.create()
                    .post()
                    .uri(baseUrl + "/rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractRerankResults)
                    .doOnSuccess(v -> usageTracker.recordUsage(model, "rerank", 0, 0, false, tokens))
                    .doOnError(e -> usageTracker.recordUsage(model, "rerank", 0, 0, true, 0));
            }));
    }

    // ===== OpenAI 协议辅助方法 =====

    private ObjectNode buildOpenAiChatBody(String model, List<Map<String, String>> messages,
                                           float temperature, int maxTokens, boolean stream,
                                           Map<String, Object> responseFormat) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", stream);

        // JSON 结构化输出（如 outline 场景需要 LLM 返回 JSON 而非 markdown）
        if (responseFormat != null && !responseFormat.isEmpty()) {
            body.putPOJO("response_format", responseFormat);
        }

        ArrayNode messagesArray = body.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msg.forEach(msgNode::put);
            messagesArray.add(msgNode);
        }
        return body;
    }

    private String extractOpenAiContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            log.error("[LlmClient] 解析 Chat 响应失败: {}", e.getMessage());
            return "";
        }
    }

    private String extractOpenAiStreamContent(String sseLine) {
        try {
            String json = sseLine.substring(5).trim();
            JsonNode root = objectMapper.readTree(json);
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private float[] extractOpenAiEmbedding(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            log.error("[LlmClient] 解析 Embedding 响应失败: {}", e.getMessage());
            return new float[0];
        }
    }

    private List<float[]> extractOpenAiEmbeddings(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            java.util.ArrayList<float[]> result = new java.util.ArrayList<>();
            for (JsonNode item : root.path("data")) {
                JsonNode embeddingNode = item.path("embedding");
                float[] vector = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    vector[i] = (float) embeddingNode.get(i).asDouble();
                }
                result.add(vector);
            }
            return result;
        } catch (Exception e) {
            log.error("[LlmClient] 解析批量 Embedding 响应失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RerankResult> extractRerankResults(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            java.util.ArrayList<RerankResult> results = new java.util.ArrayList<>();
            for (JsonNode item : root.path("results")) {
                results.add(new RerankResult(
                    item.path("index").asInt(),
                    (float) item.path("relevance_score").asDouble(),
                    item.path("document").path("text").asText("")
                ));
            }
            return results;
        } catch (Exception e) {
            log.error("[LlmClient] 解析 Rerank 响应失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ===== 数据类 =====

    public record RerankResult(int index, float relevanceScore, String text) {}

    // ===== 用量统计 helper =====

    /**
     * 字符估算 token — 中英混合按经验比例.
     * 中文 1 token ≈ 1.5 chars; 英文 1 token ≈ 4 chars; 其它按 1 token = 3 chars 兜底.
     * 这是 OpenAI 没返回 usage 字段时的兜底.
     */
    long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0L;
        long cjk = 0, ascii = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (c >= 0x20 && c <= 0x7E) ascii++;
            else other++;
        }
        // CJK: 1 token = 1.5 chars → tokens = cjk / 1.5
        // ASCII: 1 token = 4 chars → tokens = ascii / 4
        // 其它(标点、emoji 等): 1 token = 3 chars
        return Math.round(cjk / 1.5) + Math.round(ascii / 4.0) + Math.round(other / 3.0);
    }

    /**
     * 从 SSE 缓冲的"残留"里尝试捞 usage 字段.
     * OpenAI 兼容协议在最后几帧会把 usage 单独发一次(若请求里带 stream_options.include_usage=true).
     * 这里用最简单的字符串扫描 — 因为 buf 里残留的是最后几行(可能没换行结束).
     */
    void extractUsageFromBuffer(String tailBuf, long[] out) {
        if (tailBuf == null || tailBuf.isEmpty()) return;
        // 找 "usage" 键的最近一次出现
        int idx = tailBuf.lastIndexOf("\"usage\"");
        if (idx < 0) return;
        int braceStart = tailBuf.indexOf('{', idx);
        if (braceStart < 0) return;
        // 配对找右大括号
        int depth = 0, braceEnd = -1;
        for (int i = braceStart; i < tailBuf.length(); i++) {
            char c = tailBuf.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { braceEnd = i; break; }
            }
        }
        if (braceEnd < 0) return;
        String usageJson = tailBuf.substring(braceStart, braceEnd + 1);
        try {
            JsonNode node = objectMapper.readTree(usageJson);
            out[0] = node.path("prompt_tokens").asLong(0L);
            out[1] = node.path("completion_tokens").asLong(0L);
        } catch (Exception ignored) {
            // 解析失败保持原值 0
        }
    }
}

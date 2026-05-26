package run.halo.ai.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.config.AIProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 LLM HTTP 客户端 — 支持 OpenAI 兼容协议 + 百度文心协议
 *
 * 国内主流厂商（DeepSeek、阿里云、智谱、Moonshot 等）都支持 OpenAI 兼容接口。
 * 百度文心是非 OpenAI 兼容的，需要特殊处理（OAuth 鉴权、不同请求/响应格式）。
 *
 * 内部根据 baseUrl 自动判断协议类型：
 * - baseUrl 不含 "baidu" → OpenAI 兼容协议
 * - baseUrl 含 "baidu" → 百度文心协议（通过 BaiduApiClient）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    // 插件子容器没有 ObjectMapper Bean，本地创建
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AIProperties aiProperties;

    // 百度客户端缓存（key = apiKey，通常只有一个）
    private final ConcurrentHashMap<String, BaiduApiClient> baiduClients = new ConcurrentHashMap<>();

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
        if (isBaidu(baseUrl)) {
            return getBaiduClient(apiKey).chat(model, messages, temperature, maxTokens);
        }

        String url = normalizeBaseUrl(baseUrl);
        String bodyStr = buildOpenAiChatBody(model, messages, temperature, maxTokens, false).toString();

        return WebClient.create()
            .post()
            .uri(url + "/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(bodyStr)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::extractOpenAiContent);
    }

    public Flux<String> chatStream(String baseUrl, String apiKey, String model,
                                    List<Map<String, String>> messages,
                                    float temperature, int maxTokens) {
        if (isBaidu(baseUrl)) {
            return getBaiduClient(apiKey).chatStream(model, messages, temperature, maxTokens);
        }

        String url = normalizeBaseUrl(baseUrl);
        String bodyStr = buildOpenAiChatBody(model, messages, temperature, maxTokens, true).toString();

        // 关键：显式 accept text/event-stream，并以 DataBuffer 形式拿原始字节自行按行切。
        // 不用 bodyToFlux(ServerSentEvent.class)——某些 OpenAI 兼容厂商（如 qwen）返回的
        // Content-Type 不是 text/event-stream，Spring SSE Decoder 会拒绝解析；
        // 不用 bodyToFlux(String.class)——非 SSE Content-Type 时会把整段响应当一个 String，
        // 导致 [DONE] 被过滤后整个流为空。
        // chunk 边界不保证对齐到 \n（TCP 切片），用有状态累积器跨 chunk 拼完整行。
        StringBuilder buf = new StringBuilder();
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
                // 把上一次的残留 + 本次 chunk 一起处理，按 \n 切；最后一段如果没换行就留到 buf
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
            .map(this::extractOpenAiStreamContentFromJson);
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
        if (isBaidu(baseUrl)) {
            return getBaiduClient(apiKey).embed(model, text);
        }

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
            .map(this::extractOpenAiEmbedding);
    }

    public Mono<List<float[]>> embedBatch(String baseUrl, String apiKey, String model,
                                           List<String> texts, int dimensions) {
        if (isBaidu(baseUrl)) {
            return getBaiduClient(apiKey).embedBatch(model, texts);
        }

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
            .map(this::extractOpenAiEmbeddings);
    }

    // ===== Rerank =====

    /**
     * Rerank 重排序 — 仅 OpenAI 兼容协议（百度文心无此接口）
     */
    public Mono<List<RerankResult>> rerank(String baseUrl, String apiKey, String model,
                                            String query, List<String> documents, int topN) {
        if (isBaidu(baseUrl)) {
            log.warn("[LlmClient] 百度文心不支持 Rerank 接口，返回空结果");
            return Mono.just(List.of());
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        body.put("top_n", topN);
        ArrayNode docsArray = body.putArray("documents");
        documents.forEach(docsArray::add);

        return WebClient.create()
            .post()
            .uri(baseUrl + "/rerank")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body.toString())
            .retrieve()
            .bodyToMono(String.class)
            .map(this::extractRerankResults);
    }

    // ===== 协议判断 =====

    /**
     * 判断是否是百度文心 API
     *
     * 百度 baseUrl 格式：aip.baidubce.com 或含 "baidu"
     */
    private boolean isBaidu(String baseUrl) {
        return baseUrl != null && baseUrl.contains("baidu");
    }

    /**
     * 获取或创建百度客户端
     *
     * 百度用 apiKey 格式：API_KEY:SECRET_KEY
     * 按第一个 ":" 分割
     */
    private BaiduApiClient getBaiduClient(String apiKey) {
        return baiduClients.computeIfAbsent(apiKey, key -> {
            int colonIdx = key.indexOf(':');
            if (colonIdx < 0) {
                log.warn("[LlmClient] 百度模式要求 apiKey 格式为 'API_KEY:SECRET_KEY'，当前缺少 Secret Key");
                return new BaiduApiClient(objectMapper, key, "");
            }
            String ak = key.substring(0, colonIdx);
            String sk = key.substring(colonIdx + 1);
            return new BaiduApiClient(objectMapper, ak, sk);
        });
    }

    // ===== OpenAI 协议辅助方法 =====

    private ObjectNode buildOpenAiChatBody(String model, List<Map<String, String>> messages,
                                           float temperature, int maxTokens, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", stream);

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
}

package run.halo.ai.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 百度文心一言 API 客户端
 *
 * 百度文心的 API 协议与 OpenAI 不兼容，差异：
 * 1. 鉴权：API Key + Secret Key → OAuth 换 access_token（缓存在内存，30 天有效）
 * 2. Chat：system 是顶层字段而非 message role，响应取 result 字段
 * 3. Embedding：无 /embeddings 端点，使用独立接口
 * 4. 无标准 Rerank 接口（Phase 4 暂不实现）
 *
 * API 文档：https://cloud.baidu.com/doc/WENXINWORKSHOP/s/jlil56u11
 */
@Slf4j
public class BaiduApiClient {

    private static final String OAUTH_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String CHAT_BASE = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat";
    private static final String EMBED_BASE = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/embeddings";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String secretKey;

    // Token 缓存
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public BaiduApiClient(ObjectMapper objectMapper,
                          String apiKey, String secretKey) {
        this.webClient = WebClient.builder().build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    // ===== Token 管理 =====

    /**
     * 获取 access_token — 自动缓存，过期前复用
     */
    private Mono<String> getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return Mono.just(cachedToken);
        }

        return webClient.post()
            .uri(OAUTH_URL + "?grant_type=client_credentials&client_id="
                + apiKey + "&client_secret=" + secretKey)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode root = objectMapper.readTree(response);
                    String token = root.path("access_token").asText();
                    int expiresIn = root.path("expires_in").asInt(2592000); // 默认 30 天
                    cachedToken = token;
                    tokenExpiry = Instant.now().plusSeconds(expiresIn - 3600); // 提前 1h 刷新
                    log.info("[BaiduApi] 获取 access_token 成功，有效期 {} 秒", expiresIn);
                    return token;
                } catch (Exception e) {
                    log.error("[BaiduApi] 解析 OAuth 响应失败: {}", e.getMessage());
                    return "";
                }
            });
    }

    // ===== Chat Completion =====

    /**
     * 非流式 Chat — 文心格式
     */
    public Mono<String> chat(String model, List<Map<String, String>> messages,
                             float temperature, int maxTokens) {
        return getAccessToken().flatMap(token -> {
            ObjectNode body = buildBaiduChatBody(model, messages, temperature, maxTokens, false);

            return webClient.post()
                .uri(CHAT_BASE + "/" + model + "?access_token=" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractBaiduContent);
        });
    }

    /**
     * 流式 Chat (SSE) — 文心格式
     *
     * 百度 SSE：data: {"result":"token","is_end":false}
     * is_end=true 时流结束
     */
    public Flux<String> chatStream(String model, List<Map<String, String>> messages,
                                   float temperature, int maxTokens) {
        return getAccessToken().flatMapMany(token -> {
            ObjectNode body = buildBaiduChatBody(model, messages, temperature, maxTokens, true);

            return webClient.post()
                .uri(CHAT_BASE + "/" + model + "?access_token=" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data: "))
                .map(this::extractBaiduStreamContent)
                .takeUntil(tokenStr -> tokenStr == null || tokenStr.isEmpty());
            // is_end 时不再继续
        });
    }

    /**
     * 百度 Chat 请求体 — system 是顶层字段
     */
    private ObjectNode buildBaiduChatBody(String model, List<Map<String, String>> messages,
                                          float temperature, int maxTokens, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();

        // system 提示词提到顶层
        ArrayNode messagesArray = body.putArray("messages");
        for (Map<String, String> msg : messages) {
            if ("system".equals(msg.get("role"))) {
                body.put("system", msg.get("content"));
            } else {
                ObjectNode msgNode = objectMapper.createObjectNode();
                msgNode.put("role", msg.get("role"));
                msgNode.put("content", msg.get("content"));
                messagesArray.add(msgNode);
            }
        }

        if (temperature > 0) body.put("temperature", temperature);
        if (maxTokens > 0) body.put("max_output_tokens", maxTokens);
        body.put("stream", stream);

        return body;
    }

    // ===== Embedding =====

    public Mono<float[]> embed(String model, String text) {
        return getAccessToken().flatMap(token -> {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode inputArray = body.putArray("input");
            inputArray.add(text);

            return webClient.post()
                .uri(EMBED_BASE + "/" + model + "?access_token=" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractBaiduEmbedding);
        });
    }

    public Mono<List<float[]>> embedBatch(String model, List<String> texts) {
        return getAccessToken().flatMap(token -> {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode inputArray = body.putArray("input");
            texts.forEach(inputArray::add);

            return webClient.post()
                .uri(EMBED_BASE + "/" + model + "?access_token=" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractBaiduEmbeddings);
        });
    }

    // ===== 响应解析 =====

    private String extractBaiduContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            // 文心返回 result 字段
            String result = root.path("result").asText("");
            if (result.isEmpty()) {
                // 错误响应：error_msg
                String error = root.path("error_msg").asText("");
                log.error("[BaiduApi] API 错误: {}", error);
                return "";
            }
            return result;
        } catch (Exception e) {
            log.error("[BaiduApi] 解析 Chat 响应失败: {}", e.getMessage());
            return "";
        }
    }

    private String extractBaiduStreamContent(String sseLine) {
        try {
            String json = sseLine.substring(5).trim(); // 去掉 "data: "
            JsonNode root = objectMapper.readTree(json);

            // 检查错误
            if (root.has("error_msg")) {
                log.error("[BaiduApi] 流式错误: {}", root.path("error_msg").asText());
                return "";
            }

            // 百度 SSE 返回增量 result
            String result = root.path("result").asText("");
            return result;
        } catch (Exception e) {
            return "";
        }
    }

    private float[] extractBaiduEmbedding(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            log.error("[BaiduApi] 解析 Embedding 响应失败: {}", e.getMessage());
            return new float[0];
        }
    }

    private List<float[]> extractBaiduEmbeddings(String response) {
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
            log.error("[BaiduApi] 解析批量 Embedding 响应失败: {}", e.getMessage());
            return List.of();
        }
    }
}

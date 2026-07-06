package cn.rainwu.halo.ai.suite.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.endpoint.UsageLimit.LimitExceededException;
import cn.rainwu.halo.ai.suite.state.LimitGuard;
import cn.rainwu.halo.ai.suite.state.UsageTracker;

import java.util.List;
import java.util.Map;

/**
 * Unified AI model client for AI Suite.
 *
 * <p>Model providers, credentials and vendor-specific transport are owned by Halo AI Foundation.
 * This class keeps AI Suite concerns only: visitor rate limits and quota settlement.
 */
@Slf4j
@Component
public class LlmClient {

    private final AiFoundationClient aiFoundationClient;
    private final UsageTracker usageTracker;
    private final LimitGuard limitGuard;

    @Autowired
    public LlmClient(AiFoundationClient aiFoundationClient,
                     @Lazy UsageTracker usageTracker,
                     @Lazy LimitGuard limitGuard) {
        this.aiFoundationClient = aiFoundationClient;
        this.usageTracker = usageTracker;
        this.limitGuard = limitGuard;
    }

    public Mono<String> chat(String model,
                             List<Map<String, String>> messages,
                             float temperature, int maxTokens,
                             Map<String, Object> responseFormat,
                             String clientIp,
                             String scenario) {
        return aiFoundationClient.chatUsageModelName(model)
            .flatMap(usageModel -> enforceLimit(usageModel, "chat", clientIp)
                .flatMap(reserved -> reasoningMode()
                    .flatMap(reasoningMode -> aiFoundationClient.chat(model, messages, temperature, maxTokens,
                        responseFormat, scenario, reasoningMode))
                    .doFinally(signal -> usageTracker.settle(usageModel, reserved))));
    }

    public Flux<String> chatStream(String model,
                                   List<Map<String, String>> messages,
                                   float temperature, int maxTokens,
                                   Map<String, Object> responseFormat,
                                   String clientIp,
                                   String scenario) {
        return Flux.defer(() -> aiFoundationClient.chatUsageModelName(model)
            .flatMapMany(usageModel -> enforceLimit(usageModel, "chat", clientIp)
                .flatMapMany(reserved -> reasoningMode()
                    .flatMapMany(reasoningMode -> aiFoundationClient.chatStream(model, messages, temperature, maxTokens,
                        responseFormat, scenario, reasoningMode))
                    .doFinally(signal -> usageTracker.settle(usageModel, reserved)))));
    }

    /** Visitor-facing stream that preserves AI Foundation reasoning events when explicitly enabled. */
    public Flux<StreamEvent> chatStreamEvents(String model,
                                              List<Map<String, String>> messages,
                                              float temperature, int maxTokens,
                                              Map<String, Object> responseFormat,
                                              String clientIp,
                                              String scenario) {
        return Flux.defer(() -> aiFoundationClient.chatUsageModelName(model)
            .flatMapMany(usageModel -> enforceLimit(usageModel, "chat", clientIp)
                .flatMapMany(reserved -> reasoningMode().flatMapMany(reasoningMode ->
                    aiFoundationClient.chatStreamEvents(model, messages, temperature, maxTokens,
                            responseFormat, scenario, reasoningMode)
                        .filter(event -> "enabled".equals(reasoningMode) || event.isText()))
                    .doFinally(signal -> usageTracker.settle(usageModel, reserved)))));
    }

    public Flux<StreamEvent> chatStreamEvents(String model,
                                              List<Map<String, String>> messages,
                                              float temperature, int maxTokens,
                                              Map<String, Object> responseFormat,
                                              String clientIp,
                                              String scenario,
                                              String reasoningMode) {
        String effectiveMode = "enabled".equals(reasoningMode) ? "enabled" : "disabled";
        return Flux.defer(() -> aiFoundationClient.chatUsageModelName(model)
            .flatMapMany(usageModel -> enforceLimit(usageModel, "chat", clientIp)
                .flatMapMany(reserved -> aiFoundationClient.chatStreamEvents(model, messages,
                        temperature, maxTokens, responseFormat, scenario, effectiveMode)
                    .filter(event -> "enabled".equals(effectiveMode) || event.isText())
                    .doFinally(signal -> usageTracker.settle(usageModel, reserved)))));
    }

    public Mono<String> chatInternal(String model,
                                     List<Map<String, String>> messages,
                                     float temperature, int maxTokens,
                                     Map<String, Object> responseFormat,
                                     String scenario) {
        return reasoningMode().flatMap(reasoningMode -> aiFoundationClient.chat(model, messages,
            temperature, maxTokens, responseFormat, scenario, reasoningMode));
    }

    public Flux<String> chatStreamInternal(String model,
                                           List<Map<String, String>> messages,
                                           float temperature, int maxTokens,
                                           Map<String, Object> responseFormat,
                                           String scenario) {
        return reasoningMode().flatMapMany(reasoningMode -> aiFoundationClient.chatStream(model, messages,
            temperature, maxTokens, responseFormat, scenario, reasoningMode));
    }

    public Mono<float[]> embed(String model, String text, int dimensions, String scenario) {
        return enforceLimit(model, "embed", null)
            .then(aiFoundationClient.embed(model, text, dimensions, scenario));
    }

    public Mono<List<float[]>> embedBatch(String model, List<String> texts, int dimensions, String scenario) {
        return enforceLimit(model, "embed", null)
            .then(aiFoundationClient.embedBatch(model, texts, dimensions, scenario));
    }

    public Mono<List<RerankResult>> rerank(String model, String query, List<String> documents,
                                           int topN, String scenario) {
        return enforceLimit(model, "rerank", null)
            .then(aiFoundationClient.rerank(model, query, documents, topN, scenario));
    }

    private Mono<Long> enforceLimit(String model, String type, String clientIp) {
        return Mono.defer(() -> limitGuard.check(model, type, clientIp)
            .flatMap(decision -> {
                if (!decision.allowed()) {
                    return Mono.error(new LimitExceededException(
                        decision.modelName(), decision.reason()));
                }
                return Mono.just(decision.reservedTokens());
            }));
    }

    private Mono<String> reasoningMode() {
        return Mono.just("default");
    }

    public record RerankResult(int index, float relevanceScore, String text) {}

    public record StreamEvent(String type, String content) {
        public static final String TEXT = "text";
        public static final String REASONING_START = "reasoning_start";
        public static final String REASONING_DELTA = "reasoning_delta";
        public static final String REASONING_END = "reasoning_end";

        public static StreamEvent text(String content) {
            return new StreamEvent(TEXT, content);
        }

        public boolean isText() {
            return TEXT.equals(type);
        }
    }
}

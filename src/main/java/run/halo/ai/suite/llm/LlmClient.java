package run.halo.ai.suite.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.endpoint.UsageLimit.LimitExceededException;
import run.halo.ai.suite.state.LimitGuard;
import run.halo.ai.suite.state.UsageTracker;

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
        return enforceLimit(model, "chat", clientIp)
            .flatMap(reserved -> aiFoundationClient.chat(model, messages, temperature, maxTokens,
                    responseFormat, scenario)
                .doFinally(signal -> usageTracker.settle(model, reserved)));
    }

    public Flux<String> chatStream(String model,
                                   List<Map<String, String>> messages,
                                   float temperature, int maxTokens,
                                   Map<String, Object> responseFormat,
                                   String clientIp,
                                   String scenario) {
        return Flux.defer(() -> enforceLimit(model, "chat", clientIp)
            .flatMapMany(reserved -> aiFoundationClient.chatStream(model, messages, temperature, maxTokens,
                    responseFormat, scenario)
                .doFinally(signal -> usageTracker.settle(model, reserved))));
    }

    public Mono<String> chatInternal(String model,
                                     List<Map<String, String>> messages,
                                     float temperature, int maxTokens,
                                     Map<String, Object> responseFormat,
                                     String scenario) {
        return aiFoundationClient.chat(model, messages, temperature, maxTokens, responseFormat, scenario);
    }

    public Flux<String> chatStreamInternal(String model,
                                           List<Map<String, String>> messages,
                                           float temperature, int maxTokens,
                                           Map<String, Object> responseFormat,
                                           String scenario) {
        return aiFoundationClient.chatStream(model, messages, temperature, maxTokens, responseFormat, scenario);
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

    public record RerankResult(int index, float relevanceScore, String text) {}
}

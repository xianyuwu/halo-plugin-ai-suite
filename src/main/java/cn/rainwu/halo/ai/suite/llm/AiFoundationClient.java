package cn.rainwu.halo.ai.suite.llm;

import cn.rainwu.halo.ai.suite.state.UsageTracker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.chat.LanguageModelUsage;
import run.halo.aifoundation.chat.ReasoningOptions;
import run.halo.aifoundation.chat.StreamTextResult;
import run.halo.aifoundation.embedding.EmbeddingModel;
import run.halo.aifoundation.embedding.EmbeddingRequest;
import run.halo.aifoundation.embedding.EmbeddingResponse;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.part.PartType;
import run.halo.aifoundation.rerank.RerankDocument;
import run.halo.aifoundation.rerank.RerankRequest;
import run.halo.aifoundation.rerank.RerankResponse;
import run.halo.aifoundation.rerank.RerankingModel;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/**
 * Type-safe adapter for the public Halo AI Foundation SDK.
 *
 * <p>The API dependency is compile-only. At runtime the required {@code ai-foundation} plugin
 * provides the shared API classes and the enabled {@link AiModelService} implementation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiFoundationClient {

    public static final String BASE_URL = "ai-foundation://model";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RERANK_TIMEOUT = Duration.ofSeconds(30);

    private final ExtensionGetter extensionGetter;
    private final UsageTracker usageTracker;

    public static boolean isAiFoundationBaseUrl(String baseUrl) {
        return baseUrl != null && baseUrl.startsWith(BASE_URL);
    }

    public Mono<String> chatUsageModelName(String modelName) {
        return Mono.just(usageModel(modelName, "ai-foundation-default-language"));
    }

    public Mono<String> chat(String modelName, List<Map<String, String>> messages,
                             float temperature, int maxTokens,
                             Map<String, Object> responseFormat,
                             String scenario,
                             String reasoningMode) {
        String usageModel = usageModel(modelName, "ai-foundation-default-language");
        return chatOnce(modelName, messages, temperature, maxTokens, responseFormat, reasoningMode)
            .onErrorResume(error -> shouldFallbackReasoning(reasoningMode, error), error -> {
                log.warn("当前 AI Provider 不支持思考模式 {}，已回退为模型默认", reasoningMode);
                return chatOnce(modelName, messages, temperature, maxTokens, responseFormat,
                    "default");
            })
            .map(GenerateTextResult::getText)
            .map(ThinkContentFilter::strip)
            .doOnNext(reply -> usageTracker.recordUsage(usageModel, "chat", scenario,
                estimateMessageTokens(messages), estimateTokens(reply), false, 0))
            .timeout(CALL_TIMEOUT)
            .doOnError(error -> recordFailure(usageModel, "chat", scenario, error));
    }

    public Flux<String> chatStream(String modelName, List<Map<String, String>> messages,
                                   float temperature, int maxTokens,
                                   Map<String, Object> responseFormat,
                                   String scenario,
                                   String reasoningMode) {
        return Flux.defer(() -> {
            String usageModel = usageModel(modelName, "ai-foundation-default-language");
            StringBuilder content = new StringBuilder();
            Flux<String> stream = chatStreamOnce(modelName, messages, temperature, maxTokens,
                    responseFormat, reasoningMode, usageModel, scenario, content)
                .onErrorResume(error -> content.isEmpty()
                        && shouldFallbackReasoning(reasoningMode, error), error -> {
                    log.warn("当前 AI Provider 不支持思考模式 {}，已回退为模型默认",
                        reasoningMode);
                    return chatStreamOnce(modelName, messages, temperature, maxTokens,
                        responseFormat, "default", usageModel, scenario, content);
                })
                .doOnNext(content::append);
            return ThinkContentFilter.filter(stream)
                .timeout(CALL_TIMEOUT)
                .doOnError(error -> recordFailure(usageModel, "chat", scenario, error));
        });
    }

    public Flux<LlmClient.StreamEvent> chatStreamEvents(String modelName,
                                                        List<Map<String, String>> messages,
                                                        float temperature, int maxTokens,
                                                        Map<String, Object> responseFormat,
                                                        String scenario,
                                                        String reasoningMode) {
        return Flux.defer(() -> {
            String usageModel = usageModel(modelName, "ai-foundation-default-language");
            StringBuilder content = new StringBuilder();
            Flux<LlmClient.StreamEvent> stream = chatStreamEventsOnce(modelName, messages,
                    temperature, maxTokens, responseFormat, reasoningMode, usageModel, scenario,
                    content)
                .onErrorResume(error -> content.isEmpty()
                        && shouldFallbackReasoning(reasoningMode, error), error -> {
                    log.warn("当前 AI Provider 不支持思考模式 {}，已回退为模型默认",
                        reasoningMode);
                    return chatStreamEventsOnce(modelName, messages, temperature, maxTokens,
                        responseFormat, "default", usageModel, scenario, content);
                })
                .doOnNext(event -> {
                    if (event.isText() && event.content() != null) {
                        content.append(event.content());
                    }
                });
            return InlineReasoningParser.parse(stream)
                .timeout(CALL_TIMEOUT)
                .doOnError(error -> recordFailure(usageModel, "chat", scenario, error));
        });
    }

    public Mono<float[]> embed(String modelName, String text, int dimensions, String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-default-embedding");
        List<String> texts = List.of(text);
        return embeddingModel(modelName)
            .flatMap(model -> model.embed(embeddingRequest(texts, dimensions)))
            .timeout(EMBEDDING_TIMEOUT)
            .doOnNext(response -> recordEmbeddingUsage(usageModel, scenario, response, texts))
            .map(response -> response.getEmbeddings().isEmpty()
                ? new float[0] : response.getEmbeddings().getFirst())
            .doOnError(error -> recordFailure(usageModel, "embed", scenario, error));
    }

    public Mono<List<float[]>> embedBatch(String modelName, List<String> texts, int dimensions,
                                           String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-default-embedding");
        return embeddingModel(modelName)
            .flatMap(model -> model.embed(embeddingRequest(texts, dimensions)))
            .timeout(EMBEDDING_TIMEOUT)
            .doOnNext(response -> recordEmbeddingUsage(usageModel, scenario, response, texts))
            .map(EmbeddingResponse::getEmbeddings)
            .doOnError(error -> recordFailure(usageModel, "embed", scenario, error));
    }

    public Mono<List<LlmClient.RerankResult>> rerank(String modelName, String query,
                                                     List<String> documents, int topN,
                                                     String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-default-rerank");
        return rerankingModel(modelName)
            .flatMap(model -> model.rerank(rerankRequest(query, documents, topN)))
            .timeout(RERANK_TIMEOUT)
            .doOnNext(response -> recordRerankUsage(usageModel, scenario, response, query,
                documents))
            .map(this::rerankResults)
            .doOnError(error -> recordFailure(usageModel, "rerank", scenario, error));
    }

    private Mono<GenerateTextResult> chatOnce(String modelName,
                                               List<Map<String, String>> messages,
                                               float temperature, int maxTokens,
                                               Map<String, Object> responseFormat,
                                               String reasoningMode) {
        return languageModel(modelName)
            .flatMap(model -> model.generateText(generateTextRequest(messages, temperature,
                maxTokens, responseFormat, reasoningMode)));
    }

    private Flux<String> chatStreamOnce(String modelName, List<Map<String, String>> messages,
                                        float temperature, int maxTokens,
                                        Map<String, Object> responseFormat,
                                        String reasoningMode, String usageModel, String scenario,
                                        StringBuilder content) {
        return languageModel(modelName).flatMapMany(model -> {
            StreamTextResult stream = model.streamText(generateTextRequest(messages, temperature,
                maxTokens, responseFormat, reasoningMode));
            return stream.textStream().concatWith(stream.result()
                .doOnNext(result -> recordChatUsage(usageModel, scenario, result, messages,
                    content.toString()))
                .then(Mono.empty()));
        });
    }

    private Flux<LlmClient.StreamEvent> chatStreamEventsOnce(
        String modelName, List<Map<String, String>> messages, float temperature, int maxTokens,
        Map<String, Object> responseFormat, String reasoningMode, String usageModel,
        String scenario, StringBuilder content) {
        return languageModel(modelName).flatMapMany(model -> {
            StreamTextResult stream = model.streamText(generateTextRequest(messages, temperature,
                maxTokens, responseFormat, reasoningMode));
            Flux<LlmClient.StreamEvent> events = stream.fullStream().handle((part, sink) -> {
                String delta = part.getDelta() == null ? "" : part.getDelta();
                switch (part.getType()) {
                    case PartType.TEXT_DELTA -> sink.next(LlmClient.StreamEvent.text(delta));
                    case PartType.REASONING_START -> sink.next(new LlmClient.StreamEvent(
                        LlmClient.StreamEvent.REASONING_START, ""));
                    case PartType.REASONING_DELTA -> sink.next(new LlmClient.StreamEvent(
                        LlmClient.StreamEvent.REASONING_DELTA, delta));
                    case PartType.REASONING_END -> sink.next(new LlmClient.StreamEvent(
                        LlmClient.StreamEvent.REASONING_END, ""));
                    default -> { }
                }
            });
            return events.concatWith(stream.result()
                .doOnNext(result -> recordChatUsage(usageModel, scenario, result, messages,
                    content.toString()))
                .then(Mono.empty()));
        });
    }

    private Mono<AiModelService> aiModelService() {
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "AI Foundation 插件未安装、未启用或未暴露 AiModelService")));
    }

    private Mono<LanguageModel> languageModel(String modelName) {
        return aiModelService().flatMap(service -> hasText(modelName)
            ? service.languageModel(modelName) : service.languageModel());
    }

    private Mono<EmbeddingModel> embeddingModel(String modelName) {
        return aiModelService().flatMap(service -> hasText(modelName)
            ? service.embeddingModel(modelName) : service.embeddingModel());
    }

    private Mono<RerankingModel> rerankingModel(String modelName) {
        return aiModelService().flatMap(service -> hasText(modelName)
            ? service.rerankingModel(modelName) : service.rerankingModel());
    }

    private GenerateTextRequest generateTextRequest(List<Map<String, String>> messages,
                                                     float temperature, int maxTokens,
                                                     Map<String, Object> responseFormat,
                                                     String reasoningMode) {
        var builder = GenerateTextRequest.builder()
            .messages(modelMessages(messages))
            .temperature((double) temperature)
            .maxOutputTokens(maxTokens);
        if (responseFormat != null && !responseFormat.isEmpty()) {
            builder.providerOptions(Map.of("openai", Map.of("response_format", responseFormat)));
        }
        ReasoningOptions reasoning = reasoningOptions(reasoningMode);
        if (reasoning != null) {
            builder.reasoning(reasoning);
        }
        return builder.build();
    }

    private ReasoningOptions reasoningOptions(String reasoningMode) {
        return switch (reasoningMode == null ? "default" : reasoningMode) {
            case "disabled" -> ReasoningOptions.disabled();
            case "enabled" -> ReasoningOptions.enabled();
            default -> null;
        };
    }

    private List<ModelMessage> modelMessages(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of(ModelMessage.user(""));
        }
        return messages.stream().map(message -> {
            String role = message.getOrDefault("role", "user").toLowerCase(Locale.ROOT);
            String content = message.getOrDefault("content", "");
            return switch (role) {
                case "system" -> ModelMessage.system(content);
                case "assistant" -> ModelMessage.assistant(content);
                default -> ModelMessage.user(content);
            };
        }).toList();
    }

    private EmbeddingRequest embeddingRequest(List<String> texts, int dimensions) {
        var builder = EmbeddingRequest.builder().inputs(texts);
        if (dimensions > 0) {
            builder.dimensions(dimensions);
        }
        return builder.build();
    }

    private RerankRequest rerankRequest(String query, List<String> documents, int topN) {
        var builder = RerankRequest.builder()
            .query(query)
            .documents(documents.stream().map(RerankDocument::of).toList());
        if (topN > 0) {
            builder.topN(topN);
        }
        return builder.build();
    }

    private List<LlmClient.RerankResult> rerankResults(RerankResponse response) {
        if (response.getResults() == null) {
            return List.of();
        }
        List<LlmClient.RerankResult> mapped = new ArrayList<>();
        response.getResults().forEach(item -> mapped.add(new LlmClient.RerankResult(
            item.getIndex(), item.getScore() == null ? 0f : item.getScore().floatValue(),
            item.getDocument() == null ? "" : item.getDocument().getText())));
        return mapped;
    }

    private void recordChatUsage(String model, String scenario, GenerateTextResult result,
                                 List<Map<String, String>> messages, String fallbackOutput) {
        LanguageModelUsage usage = result.getTotalUsage() != null
            ? result.getTotalUsage() : result.getUsage();
        long input = usage == null || usage.getInputTokens() == null
            ? 0 : usage.getInputTokens();
        long output = usage == null || usage.getOutputTokens() == null
            ? 0 : usage.getOutputTokens();
        String text = fallbackOutput != null ? fallbackOutput : result.getText();
        if (input == 0) {
            input = estimateMessageTokens(messages);
        }
        if (output == 0) {
            output = estimateTokens(text);
        }
        usageTracker.recordUsage(model, "chat", scenario, input, output, false, 0);
    }

    private void recordEmbeddingUsage(String model, String scenario, EmbeddingResponse response,
                                      List<String> texts) {
        long tokens = response.getUsage() == null || response.getUsage().getTokens() == null
            ? 0 : response.getUsage().getTokens();
        if (tokens == 0 && texts != null) {
            tokens = texts.stream().mapToLong(this::estimateTokens).sum();
        }
        usageTracker.recordUsage(model, "embed", scenario, tokens, 0, false, 0);
    }

    private void recordRerankUsage(String model, String scenario, RerankResponse response,
                                   String query, List<String> documents) {
        long tokens = 0;
        if (response.getUsage() != null) {
            Integer inputTokens = response.getUsage().getInputTokens();
            Integer totalTokens = response.getUsage().getTotalTokens();
            tokens = inputTokens != null && inputTokens > 0
                ? inputTokens : totalTokens == null ? 0 : totalTokens;
        }
        if (tokens == 0) {
            tokens = estimateTokens(query)
                + documents.stream().mapToLong(this::estimateTokens).sum();
        }
        usageTracker.recordUsage(model, "rerank", scenario, tokens, 0, false, 0);
    }

    private void recordFailure(String model, String operation, String scenario, Throwable error) {
        usageTracker.recordUsage(model, operation, scenario, 0, 0, true, 0,
            describeError(error));
    }

    private boolean shouldFallbackReasoning(String reasoningMode, Throwable error) {
        if (!hasText(reasoningMode) || "default".equals(reasoningMode)) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("reasoning")
                && message.contains("not supported by provider type")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String usageModel(String modelName, String fallback) {
        return hasText(modelName) ? modelName : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String describeError(Throwable error) {
        Throwable current = error;
        while (current instanceof RuntimeException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof WebClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            String suffix = body == null || body.isBlank() ? "" : " body=" + body;
            String request = responseException.getRequest() == null ? ""
                : " from " + responseException.getRequest().getMethod() + " "
                    + responseException.getRequest().getURI();
            return truncate(responseException.getStatusCode() + " "
                + responseException.getStatusText() + request + suffix);
        }
        String message = current.getMessage();
        return truncate(message == null || message.isBlank()
            ? current.getClass().getSimpleName() : message);
    }

    private String truncate(String message) {
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }

    private long estimateMessageTokens(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Map<String, String> message : messages) {
            total += estimateTokens(message.getOrDefault("role", ""));
            total += estimateTokens(message.getOrDefault("content", ""));
            total += 4L;
        }
        return total;
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long cjk = 0;
        long ascii = 0;
        long other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else if (c >= 0x20 && c <= 0x7E) {
                ascii++;
            } else {
                other++;
            }
        }
        return Math.round(cjk / 1.5) + Math.round(ascii / 4.0)
            + Math.round(other / 3.0);
    }
}

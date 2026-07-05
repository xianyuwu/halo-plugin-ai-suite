package run.halo.ai.suite.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.state.UsageTracker;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional bridge to halo-dev/plugin-ai-foundation.
 *
 * <p>The AI Foundation API classes are loaded reflectively because the dependency is optional.
 * When the official plugin is absent, the built-in OpenAI-compatible path continues to work.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiFoundationClient {

    public static final String BASE_URL = "ai-foundation://model";

    private static final String AI_MODEL_SERVICE = "run.halo.aifoundation.AiModelService";
    private static final String GENERATE_TEXT_REQUEST = "run.halo.aifoundation.chat.GenerateTextRequest";
    private static final String REASONING_OPTIONS = "run.halo.aifoundation.chat.ReasoningOptions";
    private static final String MODEL_MESSAGE = "run.halo.aifoundation.message.ModelMessage";
    private static final String EMBEDDING_REQUEST = "run.halo.aifoundation.embedding.EmbeddingRequest";
    private static final String RERANK_REQUEST = "run.halo.aifoundation.rerank.RerankRequest";
    private static final String RERANK_DOCUMENT = "run.halo.aifoundation.rerank.RerankDocument";
    private static final String AI_FOUNDATION_CONFIG_MAP = "ai-foundation-configmap";
    private static final String DEFAULT_MODEL_SLOTS_KEY = "defaults";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RERANK_TIMEOUT = Duration.ofSeconds(30);

    private final ExtensionGetter extensionGetter;
    private final UsageTracker usageTracker;
    private final ReactiveExtensionClient extensionClient;

    public static boolean isAiFoundationBaseUrl(String baseUrl) {
        return baseUrl != null && baseUrl.startsWith(BASE_URL);
    }

    public Mono<String> chatUsageModelName(String modelName) {
        return usageModel(modelName, "languageModelName", "ai-foundation-language");
    }

    public Mono<String> chat(String modelName, List<Map<String, String>> messages,
                             float temperature, int maxTokens,
                             Map<String, Object> responseFormat,
                             String scenario,
                             String reasoningMode) {
        return usageModel(modelName, "languageModelName", "ai-foundation-language")
            .flatMap(usageModel -> chatOnce(modelName, messages, temperature, maxTokens,
                    responseFormat, reasoningMode)
                .onErrorResume(error -> shouldFallbackReasoning(reasoningMode, error), error -> {
                    log.warn("当前 AI Provider 不支持思考模式 {}，已回退为模型默认",
                        reasoningMode);
                    return chatOnce(modelName, messages, temperature, maxTokens, responseFormat,
                        "default");
                })
                .map(ThinkContentFilter::strip)
                .doOnNext(reply -> usageTracker.recordUsage(usageModel, "chat", scenario,
                    estimateMessageTokens(messages), estimateTokens(reply), false, 0))
                .timeout(CALL_TIMEOUT)
                .doOnError(e -> usageTracker.recordUsage(usageModel, "chat", scenario, 0, 0, true, 0, describeError(e))));
    }

    public Flux<String> chatStream(String modelName, List<Map<String, String>> messages,
                                   float temperature, int maxTokens,
                                   Map<String, Object> responseFormat,
                                   String scenario,
                                   String reasoningMode) {
        return Flux.defer(() -> usageModel(modelName, "languageModelName", "ai-foundation-language")
            .flatMapMany(usageModel -> {
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
                    .doOnError(e -> usageTracker.recordUsage(usageModel, "chat", scenario, 0, 0, true, 0, describeError(e)));
            }));
    }

    public Flux<LlmClient.StreamEvent> chatStreamEvents(String modelName,
                                                        List<Map<String, String>> messages,
                                                        float temperature, int maxTokens,
                                                        Map<String, Object> responseFormat,
                                                        String scenario,
                                                        String reasoningMode) {
        return Flux.defer(() -> usageModel(modelName, "languageModelName", "ai-foundation-language")
            .flatMapMany(usageModel -> {
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
                return InlineReasoningParser.parse(stream).timeout(CALL_TIMEOUT)
                    .doOnError(e -> usageTracker.recordUsage(usageModel, "chat", scenario,
                        0, 0, true, 0, describeError(e)));
            }));
    }

    private Mono<String> chatOnce(String modelName, List<Map<String, String>> messages,
                                  float temperature, int maxTokens,
                                  Map<String, Object> responseFormat,
                                  String reasoningMode) {
        return languageModel(modelName).flatMap(model -> {
            Object request = generateTextRequest(messages, temperature, maxTokens, responseFormat,
                reasoningMode);
            Object streamResult = invoke(model, "streamText", request.getClass(), request);
            return asMono(invoke(streamResult, "text")).map(String::valueOf);
        });
    }

    private Flux<String> chatStreamOnce(String modelName, List<Map<String, String>> messages,
                                        float temperature, int maxTokens,
                                        Map<String, Object> responseFormat,
                                        String reasoningMode,
                                        String usageModel, String scenario,
                                        StringBuilder content) {
        return languageModel(modelName).flatMapMany(model -> {
            Object request = generateTextRequest(messages, temperature, maxTokens, responseFormat,
                reasoningMode);
            Object streamResult = invoke(model, "streamText", request.getClass(), request);
            Flux<String> textStream = asFlux(invoke(streamResult, "textStream"))
                .map(String::valueOf);
            Mono<?> result = asMono(invoke(streamResult, "result"))
                .doOnNext(value -> recordChatUsage(usageModel, scenario, value, messages,
                    content.toString(), false));
            return textStream.concatWith(result.then(Mono.<String>empty()));
        });
    }

    private Flux<LlmClient.StreamEvent> chatStreamEventsOnce(String modelName,
                                                             List<Map<String, String>> messages,
                                                             float temperature, int maxTokens,
                                                             Map<String, Object> responseFormat,
                                                             String reasoningMode,
                                                             String usageModel, String scenario,
                                                             StringBuilder content) {
        return languageModel(modelName).flatMapMany(model -> {
            Object request = generateTextRequest(messages, temperature, maxTokens, responseFormat,
                reasoningMode);
            Object streamResult = invoke(model, "streamText", request.getClass(), request);
            Object fullStreamValue = invoke(streamResult, "fullStream");
            Flux<LlmClient.StreamEvent> events;
            if (fullStreamValue instanceof Flux<?> fullStream) {
                events = fullStream.handle((part, sink) -> {
                    String type = stringValue(invoke(part, "getType"));
                    String delta = stringValue(invoke(part, "getDelta"));
                    switch (type) {
                        case "text-delta" -> sink.next(LlmClient.StreamEvent.text(delta));
                        case "reasoning-start" -> sink.next(new LlmClient.StreamEvent(
                            LlmClient.StreamEvent.REASONING_START, ""));
                        case "reasoning-delta" -> sink.next(new LlmClient.StreamEvent(
                            LlmClient.StreamEvent.REASONING_DELTA, delta));
                        case "reasoning-end" -> sink.next(new LlmClient.StreamEvent(
                            LlmClient.StreamEvent.REASONING_END, ""));
                        default -> { }
                    }
                });
            } else {
                events = asFlux(invoke(streamResult, "textStream"))
                    .map(String::valueOf)
                    .map(LlmClient.StreamEvent::text);
            }
            Mono<?> result = asMono(invoke(streamResult, "result"))
                .doOnNext(value -> recordChatUsage(usageModel, scenario, value, messages,
                    content.toString(), false));
            return events.concatWith(result.then(Mono.empty()));
        });
    }

    public Mono<float[]> embed(String modelName, String text, int dimensions, String scenario) {
        return usageModel(modelName, "embeddingModelName", "ai-foundation-embedding")
            .flatMap(usageModel -> embeddingModel(modelName)
                .flatMap(model -> {
                    Object request = embeddingRequest(List.of(text), dimensions);
                    return asMono(invoke(model, "embed", request.getClass(), request));
                })
                .timeout(EMBEDDING_TIMEOUT)
                .doOnNext(response -> recordEmbeddingUsage(usageModel, scenario, response, List.of(text), false))
                .map(this::firstEmbedding)
                .doOnError(e -> usageTracker.recordUsage(usageModel, "embed", scenario, 0, 0, true, 0, describeError(e))));
    }

    public Mono<List<float[]>> embedBatch(String modelName, List<String> texts, int dimensions, String scenario) {
        return usageModel(modelName, "embeddingModelName", "ai-foundation-embedding")
            .flatMap(usageModel -> embeddingModel(modelName)
                .flatMap(model -> {
                    Object request = embeddingRequest(texts, dimensions);
                    return asMono(invoke(model, "embed", request.getClass(), request));
                })
                .timeout(EMBEDDING_TIMEOUT)
                .doOnNext(response -> recordEmbeddingUsage(usageModel, scenario, response, texts, false))
                .map(this::embeddings)
                .doOnError(e -> usageTracker.recordUsage(usageModel, "embed", scenario, 0, 0, true, 0, describeError(e))));
    }

    public Mono<List<LlmClient.RerankResult>> rerank(String modelName, String query,
                                                     List<String> documents, int topN,
                                                     String scenario) {
        return usageModel(modelName, "rerankModelName", "ai-foundation-rerank")
            .flatMap(usageModel -> rerankingModel(modelName)
                .flatMap(model -> {
                    Object request = rerankRequest(query, documents, topN);
                    return asMono(invoke(model, "rerank", request.getClass(), request));
                })
                .timeout(RERANK_TIMEOUT)
                .doOnNext(response -> recordRerankUsage(usageModel, scenario, response, query, documents, false))
                .map(this::rerankResults)
                .doOnError(e -> usageTracker.recordUsage(usageModel, "rerank", scenario, 0, 0, true, 0, describeError(e))));
    }

    private Mono<Object> languageModel(String modelName) {
        return aiModelService()
            .flatMap(service -> asMono(invokeModelResolver(service, "languageModel", modelName)));
    }

    private Mono<Object> embeddingModel(String modelName) {
        return aiModelService()
            .flatMap(service -> asMono(invokeModelResolver(service, "embeddingModel", modelName)));
    }

    private Mono<Object> rerankingModel(String modelName) {
        return aiModelService()
            .flatMap(service -> asMono(invokeModelResolver(service, "rerankingModel", modelName)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Mono<Object> aiModelService() {
        return Mono.defer(() -> {
            Class<?> serviceType = loadClass(AI_MODEL_SERVICE);
            return ((Mono<?>) extensionGetter.getEnabledExtension((Class) serviceType))
                .cast(Object.class)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "AI Foundation 插件未安装、未启用或未暴露 AiModelService")));
        });
    }

    private Object generateTextRequest(List<Map<String, String>> messages,
                                       float temperature, int maxTokens,
                                       Map<String, Object> responseFormat,
                                       String reasoningMode) {
        Object builder = staticInvoke(loadClass(GENERATE_TEXT_REQUEST), "builder");
        List<Object> modelMessages = modelMessages(messages);
        if (modelMessages.isEmpty()) {
            invoke(builder, "prompt", String.class, promptFromMessages(messages));
        } else {
            invoke(builder, "messages", List.class, modelMessages);
        }
        invoke(builder, "temperature", Double.class, (double) temperature);
        invoke(builder, "maxOutputTokens", Integer.class, maxTokens);
        if (responseFormat != null && !responseFormat.isEmpty()) {
            invoke(builder, "providerOptions", Map.class, Map.of("openai", Map.of("response_format", responseFormat)));
        }
        applyReasoning(builder, reasoningMode);
        return invoke(builder, "build");
    }

    private void applyReasoning(Object builder, String reasoningMode) {
        String factory = switch (reasoningMode == null ? "default" : reasoningMode) {
            case "disabled" -> "disabled";
            case "enabled" -> "enabled";
            default -> null;
        };
        if (factory == null) {
            return;
        }
        try {
            Class<?> reasoningType = loadClass(REASONING_OPTIONS);
            Object reasoning = staticInvoke(reasoningType, factory);
            invoke(builder, "reasoning", reasoningType, reasoning);
        } catch (IllegalStateException e) {
            // Older AI Foundation releases do not expose typed reasoning controls.
            log.debug("AI Foundation 当前版本不支持思考模式参数，已跟随模型默认", e);
        }
    }

    private boolean shouldFallbackReasoning(String reasoningMode, Throwable error) {
        if (reasoningMode == null || "default".equals(reasoningMode)) {
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

    private String promptFromMessages(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<Map<String, String>> nonSystem = messages.stream()
            .filter(message -> !"system".equalsIgnoreCase(message.getOrDefault("role", "")))
            .toList();
        if (nonSystem.isEmpty()) {
            return messages.get(messages.size() - 1).getOrDefault("content", "");
        }
        if (nonSystem.size() == 1) {
            return nonSystem.get(0).getOrDefault("content", "");
        }
        StringBuilder prompt = new StringBuilder();
        for (Map<String, String> message : nonSystem) {
            String role = message.getOrDefault("role", "user").toLowerCase(Locale.ROOT);
            String label = switch (role) {
                case "assistant" -> "Assistant";
                case "user" -> "User";
                default -> role;
            };
            prompt.append(label)
                .append(": ")
                .append(message.getOrDefault("content", ""))
                .append("\n\n");
        }
        return prompt.toString().trim();
    }

    private List<Object> modelMessages(List<Map<String, String>> messages) {
        Class<?> messageClass = loadClass(MODEL_MESSAGE);
        List<Object> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (Map<String, String> message : messages) {
            String role = message.getOrDefault("role", "user").toLowerCase(Locale.ROOT);
            String content = message.getOrDefault("content", "");
            String factory = switch (role) {
                case "system" -> "system";
                case "assistant" -> "assistant";
                default -> "user";
            };
            result.add(staticInvoke(messageClass, factory, String.class, content));
        }
        return result;
    }

    private Object embeddingRequest(List<String> texts, int dimensions) {
        Object builder = staticInvoke(loadClass(EMBEDDING_REQUEST), "builder");
        invoke(builder, "inputs", List.class, texts);
        if (dimensions > 0) {
            invoke(builder, "dimensions", Integer.class, dimensions);
        }
        return invoke(builder, "build");
    }

    private Object rerankRequest(String query, List<String> documents, int topN) {
        Object builder = staticInvoke(loadClass(RERANK_REQUEST), "builder");
        invoke(builder, "query", String.class, query);
        invoke(builder, "documents", List.class, documents.stream()
            .map(text -> staticInvoke(loadClass(RERANK_DOCUMENT), "of", String.class, text))
            .toList());
        if (topN > 0) {
            invoke(builder, "topN", Integer.class, topN);
        }
        return invoke(builder, "build");
    }

    private void recordChatUsage(String model, String scenario, Object result,
                                 List<Map<String, String>> messages,
                                 String fallbackOutput,
                                 boolean failed) {
        long input = 0;
        long output = 0;
        Object usage = invoke(result, "getTotalUsage");
        if (usage == null) {
            usage = invoke(result, "getUsage");
        }
        if (usage != null) {
            input = numberValue(invoke(usage, "getInputTokens"));
            output = numberValue(invoke(usage, "getOutputTokens"));
        }
        String text = fallbackOutput != null ? fallbackOutput : generatedText(result);
        if (input == 0 && output == 0) {
            input = estimateMessageTokens(messages);
            output = estimateTokens(text);
        } else if (output == 0) {
            output = estimateTokens(text);
        }
        usageTracker.recordUsage(model, "chat", scenario, input, output, failed, 0);
    }

    private void recordEmbeddingUsage(String model, String scenario, Object response,
                                      List<String> texts, boolean failed) {
        long tokens = 0;
        Object usage = invoke(response, "getUsage");
        if (usage != null) {
            tokens = numberValue(invoke(usage, "getTokens"));
        }
        if (tokens == 0 && texts != null) {
            tokens = texts.stream().mapToLong(this::estimateTokens).sum();
        }
        usageTracker.recordUsage(model, "embed", scenario, tokens, 0, failed, 0);
    }

    private void recordRerankUsage(String model, String scenario, Object response,
                                   String query, List<String> documents, boolean failed) {
        long tokens = 0;
        Object usage = invoke(response, "getUsage");
        if (usage != null) {
            tokens = numberValue(invoke(usage, "getInputTokens"));
            if (tokens == 0) {
                tokens = numberValue(invoke(usage, "getTotalTokens"));
            }
        }
        if (tokens == 0) {
            tokens = estimateTokens(query) + documents.stream().mapToLong(this::estimateTokens).sum();
        }
        usageTracker.recordUsage(model, "rerank", scenario, tokens, 0, failed, 0);
    }

    @SuppressWarnings("unchecked")
    private float[] firstEmbedding(Object response) {
        List<float[]> embeddings = embeddings(response);
        return embeddings.isEmpty() ? new float[0] : embeddings.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<float[]> embeddings(Object response) {
        Object value = invoke(response, "getEmbeddings");
        if (value instanceof List<?> list) {
            return (List<float[]>) list;
        }
        return List.of();
    }

    private List<LlmClient.RerankResult> rerankResults(Object response) {
        Object value = invoke(response, "getResults");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<LlmClient.RerankResult> mapped = new ArrayList<>();
        for (Object item : list) {
            int index = (int) numberValue(invoke(item, "getIndex"));
            float score = (float) doubleValue(invoke(item, "getScore"));
            Object document = invoke(item, "getDocument");
            String text = document == null ? "" : stringValue(invoke(document, "getText"));
            mapped.add(new LlmClient.RerankResult(index, score, text));
        }
        return mapped;
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("AI Foundation API 不可用，请确认已安装并启用官方 ai-foundation 插件", e);
        }
    }

    private Object staticInvoke(Class<?> type, String method) {
        try {
            return type.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw unwrap(e);
        }
    }

    private Object staticInvoke(Class<?> type, String method, Class<?> argType, Object arg) {
        try {
            return type.getMethod(method, argType).invoke(null, arg);
        } catch (ReflectiveOperationException e) {
            throw unwrap(e);
        }
    }

    private Object invoke(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw unwrap(e);
        }
    }

    private Object invoke(Object target, String method, Class<?> argType, Object arg) {
        try {
            Method m = target.getClass().getMethod(method, argType);
            return m.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            throw unwrap(e);
        }
    }

    private Object invokeModelResolver(Object service, String method, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return invoke(service, method);
        }
        return invoke(service, method, String.class, modelName);
    }

    private RuntimeException unwrap(ReflectiveOperationException e) {
        if (e instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            Throwable target = ite.getTargetException();
            return target instanceof RuntimeException re ? re : new IllegalStateException(target);
        }
        return new IllegalStateException(e);
    }

    @SuppressWarnings("unchecked")
    private Mono<Object> asMono(Object value) {
        if (value instanceof Mono<?> mono) {
            return (Mono<Object>) mono;
        }
        return Mono.error(new IllegalStateException("AI Foundation SDK 返回值不是 Mono"));
    }

    @SuppressWarnings("unchecked")
    private Flux<Object> asFlux(Object value) {
        if (value instanceof Flux<?> flux) {
            return (Flux<Object>) flux;
        }
        return Flux.error(new IllegalStateException("AI Foundation SDK 返回值不是 Flux"));
    }

    private Mono<String> usageModel(String modelName, String defaultSlotField, String fallback) {
        if (modelName != null && !modelName.isBlank()) {
            return Mono.just(modelName);
        }
        return defaultModelName(defaultSlotField)
            .filter(name -> !name.isBlank())
            .defaultIfEmpty(fallback)
            .onErrorReturn(fallback);
    }

    private Mono<String> defaultModelName(String defaultSlotField) {
        return extensionClient.fetch(ConfigMap.class, AI_FOUNDATION_CONFIG_MAP)
            .map(ConfigMap::getData)
            .map(data -> data == null ? "" : data.getOrDefault(DEFAULT_MODEL_SLOTS_KEY, ""))
            .filter(json -> json != null && !json.isBlank())
            .map(json -> {
                try {
                    JsonNode node = OBJECT_MAPPER.readTree(json);
                    return node.path(defaultSlotField).asText("");
                } catch (Exception e) {
                    throw new IllegalStateException("读取 AI Foundation 默认模型失败", e);
                }
            });
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String joinStrings(List<?> chunks) {
        StringBuilder text = new StringBuilder();
        for (Object chunk : chunks) {
            text.append(String.valueOf(chunk));
        }
        return text.toString();
    }

    private String generatedText(Object result) {
        String text = stringValue(invoke(result, "getText"));
        if (!text.isBlank()) {
            return text;
        }
        text = stringValue(invoke(result, "getOutputText"));
        if (!text.isBlank()) {
            return text;
        }
        text = contentText(invoke(result, "getContent"));
        if (!text.isBlank()) {
            return text;
        }
        Object output = invoke(result, "getOutput");
        return output == null ? "" : String.valueOf(output);
    }

    private String contentText(Object value) {
        if (!(value instanceof List<?> parts)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            String type = stringValue(invoke(part, "getType"));
            if (!type.isBlank() && !"text".equalsIgnoreCase(type)) {
                continue;
            }
            String partText = stringValue(invoke(part, "getText"));
            if (!partText.isBlank()) {
                text.append(partText);
            }
        }
        return text.toString();
    }

    private long numberValue(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0d;
    }

    private String describeError(Throwable e) {
        Throwable cur = e;
        while (cur instanceof RuntimeException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        if (cur instanceof WebClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            String bodySuffix = body == null || body.isBlank() ? "" : " body=" + body;
            String request = responseException.getRequest() == null ? ""
                : " from " + responseException.getRequest().getMethod() + " "
                    + responseException.getRequest().getURI();
            String msg = responseException.getStatusCode() + " " + responseException.getStatusText()
                + request + bodySuffix;
            return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cur.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
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
        return Math.round(cjk / 1.5) + Math.round(ascii / 4.0) + Math.round(other / 3.0);
    }
}

package run.halo.ai.suite.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.state.UsageTracker;
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
    private static final String MODEL_MESSAGE = "run.halo.aifoundation.message.ModelMessage";
    private static final String EMBEDDING_REQUEST = "run.halo.aifoundation.embedding.EmbeddingRequest";
    private static final String RERANK_REQUEST = "run.halo.aifoundation.rerank.RerankRequest";
    private static final String RERANK_DOCUMENT = "run.halo.aifoundation.rerank.RerankDocument";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RERANK_TIMEOUT = Duration.ofSeconds(30);

    private final ExtensionGetter extensionGetter;
    private final UsageTracker usageTracker;

    public static boolean isAiFoundationBaseUrl(String baseUrl) {
        return baseUrl != null && baseUrl.startsWith(BASE_URL);
    }

    public Mono<String> chat(String modelName, List<Map<String, String>> messages,
                             float temperature, int maxTokens,
                             Map<String, Object> responseFormat,
                             String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-language");
        return languageModel(modelName)
            .flatMap(model -> {
                Object request = generateTextRequest(messages, temperature, maxTokens, responseFormat);
                Object streamResult = invoke(model, "streamText", request.getClass(), request);
                return asMono(invoke(streamResult, "text"))
                    .map(String::valueOf)
                    .doOnNext(reply -> usageTracker.recordUsage(usageModel, "chat", scenario,
                        estimateMessageTokens(messages), estimateTokens(reply), false, 0));
            })
            .timeout(CALL_TIMEOUT)
            .doOnError(e -> usageTracker.recordUsage(usageModel, "chat", scenario, 0, 0, true, 0, describeError(e)));
    }

    public Flux<String> chatStream(String modelName, List<Map<String, String>> messages,
                                   float temperature, int maxTokens,
                                   Map<String, Object> responseFormat,
                                   String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-language");
        StringBuilder content = new StringBuilder();
        return Flux.defer(() -> languageModel(modelName)
            .flatMapMany(model -> {
                Object request = generateTextRequest(messages, temperature, maxTokens, responseFormat);
                Object streamResult = invoke(model, "streamText", request.getClass(), request);
                Flux<String> textStream = asFlux(invoke(streamResult, "textStream"))
                    .map(String::valueOf)
                    .doOnNext(content::append);
                Mono<?> result = asMono(invoke(streamResult, "result"))
                    .doOnNext(value -> recordChatUsage(usageModel, scenario, value, messages, content.toString(), false));
                return textStream.concatWith(result.then(Mono.<String>empty()));
            }))
            .timeout(CALL_TIMEOUT)
            .doOnError(e -> usageTracker.recordUsage(usageModel, "chat", scenario, 0, 0, true, 0, describeError(e)));
    }

    public Mono<float[]> embed(String modelName, String text, int dimensions, String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-embedding");
        return embeddingModel(modelName)
            .flatMap(model -> {
                Object request = embeddingRequest(List.of(text), dimensions);
                return asMono(invoke(model, "embed", request.getClass(), request));
            })
            .timeout(EMBEDDING_TIMEOUT)
            .doOnNext(response -> recordEmbeddingUsage(usageModel, scenario, response, List.of(text), false))
            .map(this::firstEmbedding)
            .doOnError(e -> usageTracker.recordUsage(usageModel, "embed", scenario, 0, 0, true, 0, describeError(e)));
    }

    public Mono<List<float[]>> embedBatch(String modelName, List<String> texts, int dimensions, String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-embedding");
        return embeddingModel(modelName)
            .flatMap(model -> {
                Object request = embeddingRequest(texts, dimensions);
                return asMono(invoke(model, "embed", request.getClass(), request));
            })
            .timeout(EMBEDDING_TIMEOUT)
            .doOnNext(response -> recordEmbeddingUsage(usageModel, scenario, response, texts, false))
            .map(this::embeddings)
            .doOnError(e -> usageTracker.recordUsage(usageModel, "embed", scenario, 0, 0, true, 0, describeError(e)));
    }

    public Mono<List<LlmClient.RerankResult>> rerank(String modelName, String query,
                                                     List<String> documents, int topN,
                                                     String scenario) {
        String usageModel = usageModel(modelName, "ai-foundation-rerank");
        return rerankingModel(modelName)
            .flatMap(model -> {
                Object request = rerankRequest(query, documents, topN);
                return asMono(invoke(model, "rerank", request.getClass(), request));
            })
            .timeout(RERANK_TIMEOUT)
            .doOnNext(response -> recordRerankUsage(usageModel, scenario, response, query, documents, false))
            .map(this::rerankResults)
            .doOnError(e -> usageTracker.recordUsage(usageModel, "rerank", scenario, 0, 0, true, 0, describeError(e)));
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
                                       Map<String, Object> responseFormat) {
        Object builder = staticInvoke(loadClass(GENERATE_TEXT_REQUEST), "builder");
        String system = systemMessage(messages);
        if (!system.isBlank()) {
            invoke(builder, "system", String.class, system);
        }
        invoke(builder, "prompt", String.class, promptFromMessages(messages));
        invoke(builder, "temperature", Double.class, (double) temperature);
        invoke(builder, "maxOutputTokens", Integer.class, maxTokens);
        if (responseFormat != null && !responseFormat.isEmpty()) {
            invoke(builder, "providerOptions", Map.class, Map.of("openai", Map.of("response_format", responseFormat)));
        }
        return invoke(builder, "build");
    }

    private String systemMessage(List<Map<String, String>> messages) {
        if (messages == null) {
            return "";
        }
        return messages.stream()
            .filter(message -> "system".equalsIgnoreCase(message.getOrDefault("role", "")))
            .map(message -> message.getOrDefault("content", ""))
            .filter(content -> !content.isBlank())
            .findFirst()
            .orElse("");
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

    private String usageModel(String modelName, String fallback) {
        return modelName == null || modelName.isBlank() ? fallback : modelName;
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
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cur.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
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

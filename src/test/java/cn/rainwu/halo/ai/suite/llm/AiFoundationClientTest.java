package cn.rainwu.halo.ai.suite.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.state.UsageTracker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.embedding.EmbeddingModel;
import run.halo.aifoundation.embedding.EmbeddingRequest;
import run.halo.aifoundation.embedding.EmbeddingResponse;
import run.halo.aifoundation.rerank.RerankDocument;
import run.halo.aifoundation.rerank.RerankRequest;
import run.halo.aifoundation.rerank.RerankResponse;
import run.halo.aifoundation.rerank.RerankResult;
import run.halo.aifoundation.rerank.RerankingModel;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

class AiFoundationClientTest {

    private final ExtensionGetter extensionGetter = mock(ExtensionGetter.class);
    private final UsageTracker usageTracker = mock(UsageTracker.class);
    private final AiModelService modelService = mock(AiModelService.class);
    private final AiFoundationClient client = new AiFoundationClient(extensionGetter, usageTracker);

    @Test
    void streamsTextAndRecordsFinalProviderUsageOnce() {
        var model = streamingLanguageModel();
        var stream = mock(run.halo.aifoundation.chat.StreamTextResult.class);
        when(model.streamText(any(GenerateTextRequest.class))).thenReturn(stream);
        when(stream.textStream()).thenReturn(reactor.core.publisher.Flux.just("hello", " world"));
        when(stream.result()).thenReturn(Mono.just(GenerateTextResult.builder().text("hello world")
            .usage(run.halo.aifoundation.chat.LanguageModelUsage.builder().inputTokens(7).outputTokens(9).build()).build()));
        assertThat(client.chatStream("", List.of(Map.of("role", "user", "content", "hello")),
            .2f, 128, null, "test", "default").collectList().block()).containsExactly("hello", " world");
        verify(usageTracker).recordUsage("ai-foundation-default-language", "chat", "test", 7L, 9L, false, 0);
        verify(model).streamText(any(GenerateTextRequest.class));
    }

    @Test
    void mapsNativeReasoningAndTextEventsOnFormalSdk() {
        var model = streamingLanguageModel();
        var stream = mock(run.halo.aifoundation.chat.StreamTextResult.class);
        when(model.streamText(any(GenerateTextRequest.class))).thenReturn(stream);
        when(stream.fullStream()).thenReturn(reactor.core.publisher.Flux.just(
            run.halo.aifoundation.part.TextStreamPart.reasoningStart("r"),
            run.halo.aifoundation.part.TextStreamPart.reasoningDelta("r", "consider", Map.of()),
            run.halo.aifoundation.part.TextStreamPart.reasoningEnd("r"),
            run.halo.aifoundation.part.TextStreamPart.textDelta("t", "answer")));
        when(stream.result()).thenReturn(Mono.just(GenerateTextResult.builder().text("answer").build()));
        var events = client.chatStreamEvents("", List.of(Map.of("role", "user", "content", "hello")),
            .2f, 128, null, "test", "enabled").collectList().block();
        assertThat(events).extracting(LlmClient.StreamEvent::type).containsExactly(
            LlmClient.StreamEvent.REASONING_START, LlmClient.StreamEvent.REASONING_DELTA,
            LlmClient.StreamEvent.REASONING_END, LlmClient.StreamEvent.TEXT);
        assertThat(events).extracting(LlmClient.StreamEvent::content).containsExactly("", "consider", "", "answer");
    }

    @Test
    void terminalStreamFailureIsPropagatedWithoutRepeatingGeneration() {
        var model = streamingLanguageModel();
        var stream = mock(run.halo.aifoundation.chat.StreamTextResult.class);
        when(model.streamText(any(GenerateTextRequest.class))).thenReturn(stream);
        when(stream.fullStream()).thenReturn(reactor.core.publisher.Flux.just(
            run.halo.aifoundation.part.TextStreamPart.textDelta("t", "partial")));
        when(stream.result()).thenReturn(Mono.error(new IllegalStateException("provider interrupted")));
        assertThatThrownBy(() -> client.chatStreamEvents("", List.of(Map.of("role", "user", "content", "hello")),
            .2f, 128, null, "test", "default").collectList().block())
            .hasMessageContaining("provider interrupted");
        verify(model, times(1)).streamText(any(GenerateTextRequest.class));
    }

    private LanguageModel streamingLanguageModel() {
        var model = mock(LanguageModel.class);
        when(extensionGetter.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(modelService));
        when(modelService.languageModel()).thenReturn(Mono.just(model));
        return model;
    }

    @Test
    void doubaoUsesUrlWhileOtherProvidersKeepBase64WithoutRetry() {
        var model = mock(run.halo.aifoundation.image.ImageGenerationModel.class);
        var info = new run.halo.aifoundation.model.ProviderInfo();
        info.setProviderType("doubao");
        when(model.providerInfo()).thenReturn(info);
        when(extensionGetter.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(modelService));
        when(modelService.imageGenerationModel("image")).thenReturn(Mono.just(model));
        when(model.generateImage(any(run.halo.aifoundation.image.GenerateImageRequest.class)))
            .thenReturn(Mono.just(run.halo.aifoundation.image.GenerateImageResult.builder().images(List.of()).build()));
        client.generateImage("image", "prompt", List.of(), "2048x2048", "test").block();
        info.setProviderType("dashscope");
        client.generateImage("image", "prompt", List.of(), "1024x1024", "test").block();
        var captor = ArgumentCaptor.forClass(run.halo.aifoundation.image.GenerateImageRequest.class);
        verify(model, times(2)).generateImage(captor.capture());
        assertThat(captor.getAllValues()).extracting(run.halo.aifoundation.image.GenerateImageRequest::getResponseFormat)
            .containsExactly(run.halo.aifoundation.image.ImageResponseFormat.URL, run.halo.aifoundation.image.ImageResponseFormat.BASE64);
    }

    @Test
    void modelPrecheckUsesDefaultWithoutGeneratingOrRecordingUsage() {
        var model = mock(run.halo.aifoundation.image.ImageGenerationModel.class);
        var image = new run.halo.aifoundation.capability.ImageGenerationCapability();
        image.setTextToImage(true);
        image.setImageToImage(true);
        when(model.capabilities()).thenReturn(
            run.halo.aifoundation.capability.ModelCapabilities.imageGeneration(image));
        when(extensionGetter.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(modelService));
        when(modelService.imageGenerationModel()).thenReturn(Mono.just(model));
        assertThat(client.imageModelStatus("").block().available()).isTrue();
        verify(modelService).imageGenerationModel();
        org.mockito.Mockito.verifyNoInteractions(usageTracker);
        verify(model, org.mockito.Mockito.never()).generateImage(any(run.halo.aifoundation.image.GenerateImageRequest.class));
    }

    @Test
    void modelPrecheckBlocksSelectedModelWithoutReferenceImageCapability() {
        var model = mock(run.halo.aifoundation.image.ImageGenerationModel.class);
        var image = new run.halo.aifoundation.capability.ImageGenerationCapability();
        image.setTextToImage(true);
        image.setImageToImage(false);
        when(model.capabilities()).thenReturn(
            run.halo.aifoundation.capability.ModelCapabilities.imageGeneration(image));
        when(extensionGetter.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(modelService));
        when(modelService.imageGenerationModel("selected")).thenReturn(Mono.just(model));
        assertThat(client.imageModelStatus("selected").block().reason()).isEqualTo("capability");
        verify(modelService).imageGenerationModel("selected");
        org.mockito.Mockito.verifyNoInteractions(usageTracker);
    }

    @Test
    void modelPrecheckDistinguishesMissingDefaultFromInspectionFailure() {
        when(extensionGetter.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(modelService));
        when(modelService.imageGenerationModel()).thenReturn(Mono.error(
            new run.halo.aifoundation.exception.DefaultModelNotConfiguredException("image")));
        assertThat(client.imageModelStatus(null).block().reason()).isEqualTo("missing");
        when(modelService.imageGenerationModel()).thenReturn(Mono.error(new IllegalStateException("offline")));
        assertThat(client.imageModelStatus(null).block().reason()).isEqualTo("unavailable");
        when(extensionGetter.getEnabledExtension(AiModelService.class)).thenReturn(Mono.empty());
        assertThat(client.imageModelStatus(null).block().available()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(usageTracker);
    }

    @Test
    void structuredJsonUsesPortableOutputSpecWithoutProviderOptions() {
        LanguageModel model = mock(LanguageModel.class);
        when(extensionGetter.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(modelService));
        when(modelService.languageModel()).thenReturn(Mono.just(model));
        when(model.generateText(any(GenerateTextRequest.class)))
            .thenReturn(Mono.just(GenerateTextResult.builder().text("{\"ok\":true}").build()));
        assertThat(client.chat("", List.of(Map.of("role", "user", "content", "json")),
            .2f, 128, Map.of("type", "json_object"), "test", "default").block()).isEqualTo("{\"ok\":true}");
        var captor = ArgumentCaptor.forClass(GenerateTextRequest.class);
        verify(model).generateText(captor.capture());
        assertThat(captor.getValue().getOutput().getType()).isEqualTo(run.halo.aifoundation.schema.OutputType.JSON);
    }

    @Test
    void usesDefaultLanguageModelAndTypedRequestWhenModelNameIsBlank() {
        LanguageModel model = mock(LanguageModel.class);
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(modelService));
        when(modelService.languageModel()).thenReturn(Mono.just(model));
        when(model.generateText(any(GenerateTextRequest.class)))
            .thenReturn(Mono.just(GenerateTextResult.builder().text("ok").build()));

        String reply = client.chat("", List.of(Map.of("role", "user", "content", "hello")),
            0.2f, 128, null, "test", "enabled").block();

        assertThat(reply).isEqualTo("ok");
        ArgumentCaptor<GenerateTextRequest> request =
            ArgumentCaptor.forClass(GenerateTextRequest.class);
        verify(model).generateText(request.capture());
        assertThat(request.getValue().getMessages()).hasSize(1);
        assertThat(request.getValue().getReasoning()).isNotNull();
        verify(modelService).languageModel();
    }

    @Test
    void omitsDimensionsWhenNativeVectorAlreadyMatches() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(modelService));
        when(modelService.embeddingModel("embedding-model")).thenReturn(Mono.just(model));
        when(model.embed(any(EmbeddingRequest.class))).thenReturn(Mono.just(
            EmbeddingResponse.builder().embeddings(List.of(new float[] {1f, 2f})).build()));

        float[] result = client.embed("embedding-model", "hello", 2, "test").block();

        assertThat(result).containsExactly(1f, 2f);
        ArgumentCaptor<EmbeddingRequest> request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model).embed(request.capture());
        assertThat(request.getValue().getDimensions()).isNull();
    }

    @Test
    void requestsDimensionsForVariableDimensionModelAndCachesMode() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(modelService));
        when(modelService.embeddingModel("embedding-model")).thenReturn(Mono.just(model));
        when(model.embed(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            int size = request.getDimensions() == null ? 3 : request.getDimensions();
            return Mono.just(EmbeddingResponse.builder()
                .embeddings(List.of(new float[size]))
                .build());
        });

        assertThat(client.embed("embedding-model", "first", 2, "test").block()).hasSize(2);
        assertThat(client.embed("embedding-model", "second", 2, "test").block()).hasSize(2);

        ArgumentCaptor<EmbeddingRequest> requests = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model, times(3)).embed(requests.capture());
        assertThat(requests.getAllValues())
            .extracting(EmbeddingRequest::getDimensions)
            .containsExactly(null, 2, 2);
    }

    @Test
    void reportsClearErrorWhenConfiguredDimensionCannotBeProduced() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(modelService));
        when(modelService.embeddingModel("fixed-model")).thenReturn(Mono.just(model));
        when(model.embed(any(EmbeddingRequest.class))).thenReturn(Mono.just(
            EmbeddingResponse.builder().embeddings(List.of(new float[3])).build()));

        assertThatThrownBy(() -> client.embed("fixed-model", "hello", 2, "test").block())
            .hasMessageContaining("原生维度为 3")
            .hasMessageContaining("显式请求 2 维");
    }

    @Test
    void mapsTypedRerankResponseToSuiteResult() {
        RerankingModel model = mock(RerankingModel.class);
        when(extensionGetter.getEnabledExtension(AiModelService.class))
            .thenReturn(Mono.just(modelService));
        when(modelService.rerankingModel()).thenReturn(Mono.just(model));
        when(model.rerank(any(RerankRequest.class))).thenReturn(Mono.just(
            RerankResponse.builder().results(List.of(RerankResult.builder()
                .index(0)
                .score(0.91)
                .document(RerankDocument.of("document"))
                .build())).build()));

        List<LlmClient.RerankResult> result =
            client.rerank("", "query", List.of("document"), 1, "test").block();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().relevanceScore()).isEqualTo(0.91f);
        assertThat(result.getFirst().text()).isEqualTo("document");
        verify(modelService).rerankingModel();
    }
}

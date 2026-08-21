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

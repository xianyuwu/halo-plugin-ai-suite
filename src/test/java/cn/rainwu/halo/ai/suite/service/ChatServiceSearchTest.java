package cn.rainwu.halo.ai.suite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.intent.PipelineExecutor;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.rag.RAGPipeline;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

class ChatServiceSearchTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final RAGPipeline ragPipeline = mock(RAGPipeline.class);
    private final ChatService chatService = new ChatService(llmClient, mock(AIProperties.class),
        ragPipeline, mock(ReactiveExtensionClient.class), mock(ExternalUrlSupplier.class),
        mock(IntentDetector.class), mock(IntentRouteService.class), mock(PipelineExecutor.class));

    @Test
    void emptySearchEvidenceReturnsSiteMessageWithoutCallingModel() {
        when(ragPipeline.retrieve("作者去过西藏吗？", List.of()))
            .thenReturn(Mono.just(RAGPipeline.RAGContext.empty()));

        String answer = chatService.searchAnswerStreamWithCitations("作者去过西藏吗？")
            .flatMap(response -> response.stream().collectList()
                .map(parts -> String.join("", parts)))
            .block();

        assertThat(answer).contains("没有检索到足够的站内依据");
        verifyNoInteractions(llmClient);
    }

    @Test
    void failedSearchRetrievalReturnsRetryMessageWithoutCallingModel() {
        when(ragPipeline.retrieve("博客里有没有写量子计算？", List.of()))
            .thenReturn(Mono.error(new IllegalStateException("retrieval unavailable")));

        String answer = chatService.searchAnswerStreamWithCitations("博客里有没有写量子计算？")
            .flatMap(response -> response.stream().collectList()
                .map(parts -> String.join("", parts)))
            .block();

        assertThat(answer).contains("站内检索暂时失败");
        verifyNoInteractions(llmClient);
    }
}

package cn.rainwu.halo.ai.suite.rag;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class ReindexServiceIdempotencyTest {

    @Test
    void shouldSkipAllModelWorkWhenStoredFingerprintMatches() throws Exception {
        ReactiveExtensionClient extensionClient = mock(ReactiveExtensionClient.class);
        PostContentService contentService = mock(PostContentService.class);
        DocumentChunker chunker = mock(DocumentChunker.class);
        LuceneIndexService indexService = mock(LuceneIndexService.class);
        LlmClient llmClient = mock(LlmClient.class);
        AIProperties properties = mock(AIProperties.class);
        ReindexService service = new ReindexService(extensionClient, contentService, chunker,
            indexService, llmClient, properties);

        AIProperties.ModelConfig model = modelConfig();
        AIProperties.ChunkConfig chunk = chunkConfig();
        Post post = publicPost("post-a", "标题");
        String content = "没有变化的正文";
        String fingerprint = PostIndexFingerprint.compute("标题", content, model, chunk);

        when(properties.getModelConfig()).thenReturn(Mono.just(model));
        when(properties.getChunkConfig()).thenReturn(Mono.just(chunk));
        when(extensionClient.fetch(Post.class, "post-a")).thenReturn(Mono.just(post));
        when(contentService.getReleaseContent("post-a"))
            .thenReturn(Mono.just(ContentWrapper.builder().raw(content).build()));
        when(indexService.getPostFingerprint("post-a")).thenReturn(fingerprint);

        ReindexService.PostReindexResult result = service.reindexPostIfChanged("post-a").block();

        assertTrue(result != null && result.skipped());
        verifyNoInteractions(chunker, llmClient);
        verify(indexService, never()).replacePost(
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString());
    }

    private static Post publicPost(String name, String title) {
        Post post = mock(Post.class);
        Post.PostSpec spec = new Post.PostSpec();
        spec.setTitle(title);
        spec.setPublish(true);
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        Metadata metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(Map.of());
        when(post.getSpec()).thenReturn(spec);
        when(post.getMetadata()).thenReturn(metadata);
        when(post.isPublished()).thenReturn(true);
        when(post.isDeleted()).thenReturn(false);
        return post;
    }

    private static AIProperties.ModelConfig modelConfig() {
        AIProperties.ModelConfig model = new AIProperties.ModelConfig();
        model.setAiFoundationEmbeddingModelName("embedding-model");
        model.setEmbeddingDimensions(1024);
        model.setAiFoundationChatModelName("chat-model");
        return model;
    }

    private static AIProperties.ChunkConfig chunkConfig() {
        AIProperties.ChunkConfig chunk = new AIProperties.ChunkConfig();
        chunk.setChunkMode("auto");
        chunk.setChunkSize(800);
        chunk.setChunkOverlap(100);
        chunk.setChunkSeparator("\n\n");
        chunk.setMarkdownHeadingAware(true);
        chunk.setCleanWhitespace(true);
        chunk.setMinChunkSize(100);
        chunk.setSentenceAware(true);
        chunk.setAutoKeywords(true);
        chunk.setAutoKeywordsCount(5);
        chunk.setKeywordsMaxTokens(2048);
        chunk.setKeywordsBatchSize(1);
        return chunk;
    }
}

package cn.rainwu.halo.ai.suite.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.rainwu.halo.ai.suite.config.AIProperties.ChunkConfig;
import cn.rainwu.halo.ai.suite.config.AIProperties.ModelConfig;
import org.junit.jupiter.api.Test;

class PostIndexFingerprintTest {

    @Test
    void shouldBeStableForSameInputs() {
        ModelConfig model = modelConfig();
        ChunkConfig chunk = chunkConfig();

        String first = PostIndexFingerprint.compute("标题", "正文", model, chunk);
        String second = PostIndexFingerprint.compute("标题", "正文", model, chunk);

        assertEquals(first, second);
    }

    @Test
    void shouldChangeWhenContentOrIndexConfigurationChanges() {
        ModelConfig model = modelConfig();
        ChunkConfig chunk = chunkConfig();
        String baseline = PostIndexFingerprint.compute("标题", "正文", model, chunk);

        assertNotEquals(baseline,
            PostIndexFingerprint.compute("标题", "修改后的正文", model, chunk));

        chunk.setChunkSize(chunk.getChunkSize() + 1);
        assertNotEquals(baseline, PostIndexFingerprint.compute("标题", "正文", model, chunk));
    }

    @Test
    void shouldIgnoreChatModelWhenAutomaticKeywordsAreDisabled() {
        ModelConfig model = modelConfig();
        ChunkConfig chunk = chunkConfig();
        chunk.setAutoKeywords(false);
        String baseline = PostIndexFingerprint.compute("标题", "正文", model, chunk);

        model.setAiFoundationChatModelName("another-chat-model");

        assertEquals(baseline, PostIndexFingerprint.compute("标题", "正文", model, chunk));
    }

    @Test
    void shouldChangeWhenKeywordModelChangesAndAutomaticKeywordsAreEnabled() {
        ModelConfig model = modelConfig();
        ChunkConfig chunk = chunkConfig();
        String baseline = PostIndexFingerprint.compute("标题", "正文", model, chunk);

        model.setAiFoundationChatModelName("another-chat-model");

        assertNotEquals(baseline, PostIndexFingerprint.compute("标题", "正文", model, chunk));
    }

    private static ModelConfig modelConfig() {
        ModelConfig model = new ModelConfig();
        model.setAiFoundationEmbeddingModelName("embedding-model");
        model.setEmbeddingDimensions(1024);
        model.setAiFoundationChatModelName("chat-model");
        return model;
    }

    private static ChunkConfig chunkConfig() {
        ChunkConfig chunk = new ChunkConfig();
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

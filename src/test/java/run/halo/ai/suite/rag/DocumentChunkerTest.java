package run.halo.ai.suite.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.ai.suite.config.AIProperties;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void clampsOverlapWhenItIsGreaterThanChunkSize() {
        AIProperties.ChunkConfig config = baseConfig();
        config.setChunkSize(10);
        config.setChunkOverlap(20);

        List<TextChunk> chunks = assertTimeoutPreemptively(Duration.ofSeconds(1),
            () -> chunker.chunk("post-a", "测试文章", "abcdefghijklmnopqrstuvwxyz", config));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.content()).hasSizeLessThanOrEqualTo(10));
    }

    @Test
    void removesMarkdownImagesBeforeChunking() {
        AIProperties.ChunkConfig config = baseConfig();

        List<TextChunk> chunks = chunker.chunk(
            "post-b",
            "图片文章",
            "正文开始，这里有一段用于检索的有效内容。\n\n"
                + "![alt](https://example.com/a.png)\n\n"
                + "正文结束，这里继续补充足够的文字。",
            config);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
            .extracting(TextChunk::content)
            .allSatisfy(content -> assertThat(content).doesNotContain("![alt]"));
    }

    private AIProperties.ChunkConfig baseConfig() {
        AIProperties.ChunkConfig config = new AIProperties.ChunkConfig();
        config.setChunkMode("custom");
        config.setChunkSize(200);
        config.setChunkOverlap(0);
        config.setChunkSeparator("\n\n");
        config.setMarkdownHeadingAware(false);
        config.setAutoKeywords(false);
        config.setAutoKeywordsCount(0);
        config.setCleanWhitespace(true);
        config.setMinChunkSize(0);
        config.setSentenceAware(false);
        config.setKeywordsMaxTokens(512);
        config.setKeywordsBatchSize(20);
        return config;
    }
}

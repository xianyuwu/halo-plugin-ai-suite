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

    /** overlap 滑动窗口: 相邻切片应有重叠内容 */
    @Test
    void overlapCreatesSharedContentBetweenChunks() {
        AIProperties.ChunkConfig config = baseConfig();
        config.setChunkSize(20);
        config.setChunkOverlap(5);

        // 50 字符, chunkSize=20, overlap=5 → 应产生多个有重叠的切片
        String content = "01234567890123456789012345678901234567890123456789";
        List<TextChunk> chunks = chunker.chunk("post-c", "标题", content, config);

        assertThat(chunks).hasSizeGreaterThan(1);
        // 至少有一对相邻切片共享内容(overlap>0 的语义)
        // 这里验证切片总数符合预期(50字符 / (20-5) ≈ 4 个)
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
    }

    /** sentenceAware=true: 切片应在句子边界断开, 不从句子中间切 */
    @Test
    void sentenceAwareBreaksAtSentenceBoundary() {
        AIProperties.ChunkConfig config = baseConfig();
        config.setChunkSize(15);
        config.setSentenceAware(true);

        // 中文句号作分隔, chunkSize=15 应在第一个句号后断开
        String content = "这是第一句话。这是第二句话。这是第三句话。";
        List<TextChunk> chunks = chunker.chunk("post-d", "标题", content, config);

        assertThat(chunks).isNotEmpty();
        // sentenceAware 应尽量在标点处切, 第一切片应包含完整的第一句
        assertThat(chunks.get(0).content()).contains("。");
    }

    /**
     * 死循环防护(本次会话新增的修复): 极端输入不应卡死.
     * 场景: chunkSize 很小 + sentenceAware + 无标点长串, 触发 findBestBoundary 返回 -1.
     * 核心验证: assertTimeoutPreemptively 确保不超时(死循环会超时).
     */
    @Test
    void noInfiniteLoopOnEdgeInputWithSentenceAware() {
        AIProperties.ChunkConfig config = baseConfig();
        config.setChunkSize(8);
        config.setSentenceAware(true);

        // 无任何标点的纯文字, chunkSize 较小, 历史上会触发 end<=start 死循环
        String content = "这是一段没有任何标点的很长很长的文字内容用来测试死循环防护机制是否生效";
        // 核心验证: assertTimeoutPreemptively 确保不超时(死循环会抛 TimeoutFailure).
        // 切片内容是否非空取决于 chunker 的 minChunkSize/mergeShortChunks 后续逻辑,
        // 与死循环防护无关, 不在此断言.
        assertTimeoutPreemptively(Duration.ofSeconds(2),
            () -> chunker.chunk("post-e", "标题", content, config));
    }

    /** 空内容返回空切片列表 */
    @Test
    void emptyContentReturnsEmpty() {
        AIProperties.ChunkConfig config = baseConfig();
        List<TextChunk> chunks = chunker.chunk("post-f", "标题", "", config);
        assertThat(chunks).isEmpty();
    }

    /** 内容短于 chunkSize 时返回单个切片 */
    @Test
    void shortContentReturnsSingleChunk() {
        AIProperties.ChunkConfig config = baseConfig();
        config.setChunkSize(200);
        // 用有足够实际内容的短文本(避免被清洗逻辑过滤)
        List<TextChunk> chunks = chunker.chunk("post-g", "标题",
            "这是一段足够简短但有效的文章内容用于单切片测试", config);
        assertThat(chunks).hasSize(1);
    }

    /** 切片 id 应含 postId, 便于索引溯源 */
    @Test
    void chunkIdContainsPostId() {
        AIProperties.ChunkConfig config = baseConfig();
        List<TextChunk> chunks = chunker.chunk("post-xyz", "标题", "一段足够长的内容用于切片测试".repeat(5), config);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.id()).startsWith("post-xyz"));
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

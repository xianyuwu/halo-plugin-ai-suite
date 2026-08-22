package cn.rainwu.halo.ai.suite.rag;

import cn.rainwu.halo.ai.suite.config.AIProperties.ChunkConfig;
import cn.rainwu.halo.ai.suite.config.AIProperties.ModelConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 计算会影响单篇文章索引结果的稳定指纹。
 *
 * <p>指纹在任何 LLM 或 Embedding 调用前计算，用于确保相同正文和配置的
 * 重复 Post 事件是幂等的。格式版本变更时应更换 {@link #FORMAT_VERSION}。</p>
 */
final class PostIndexFingerprint {

    private static final String FORMAT_VERSION = "post-index-v1";

    private PostIndexFingerprint() {
    }

    static String compute(String title, String content, ModelConfig model, ChunkConfig chunk) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "version", FORMAT_VERSION);
        append(canonical, "title", title);
        append(canonical, "content", content);
        append(canonical, "embeddingModel", model.getEffectiveEmbeddingModel());
        append(canonical, "embeddingDimensions", model.getEmbeddingDimensions());
        append(canonical, "chunkMode", chunk.getChunkMode());
        append(canonical, "chunkSize", chunk.getChunkSize());
        append(canonical, "chunkOverlap", chunk.getChunkOverlap());
        append(canonical, "chunkSeparator", chunk.getChunkSeparator());
        append(canonical, "markdownHeadingAware", chunk.isMarkdownHeadingAware());
        append(canonical, "cleanWhitespace", chunk.isCleanWhitespace());
        append(canonical, "minChunkSize", chunk.getMinChunkSize());
        append(canonical, "sentenceAware", chunk.isSentenceAware());
        append(canonical, "autoKeywords", chunk.isAutoKeywords());
        append(canonical, "autoKeywordsCount", chunk.getAutoKeywordsCount());
        append(canonical, "keywordsMaxTokens", chunk.getKeywordsMaxTokens());
        append(canonical, "keywordsBatchSize", chunk.getKeywordsBatchSize());
        if (chunk.isAutoKeywords() && chunk.getAutoKeywordsCount() > 0) {
            append(canonical, "keywordModel", model.getEffectiveChatModel());
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String key, Object value) {
        String normalized = value == null ? "" : String.valueOf(value);
        target.append(key.length()).append(':').append(key)
            .append('=').append(normalized.length()).append(':').append(normalized).append('\n');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}

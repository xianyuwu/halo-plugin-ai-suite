package cn.rainwu.halo.ai.suite.rag;

/**
 * 检索命中的文档 — HybridRetriever 返回的结果
 *
 * 包含切片内容 + 来源信息 + 相关性分数，
 * 最终注入 LLM 的 system prompt 作为上下文
 */
public record RetrievedDocument(
    String postId,
    String postTitle,
    String content,
    float score,
    int chunkIndex
) {}

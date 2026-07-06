package cn.rainwu.halo.ai.suite.rag;

import java.util.List;

/**
 * 一个文本切片 — 文章被切成多段后的最小检索单元
 *
 * 每个切片保留来源信息（postId, title, chunkIndex），
 * 这样检索命中后能告诉用户"这段内容来自哪篇文章"
 */
public record TextChunk(
    String id,              // postId_chunkIndex，唯一标识
    String postId,          // Halo Post 的 metadata.name
    String postTitle,       // 文章标题
    String content,         // 切片文本内容
    int chunkIndex,         // 在文章中的顺序（从 0 开始）
    List<String> keywords   // 自动提取的关键词列表
) {}

package run.halo.ai.suite.service;

/**
 * 用户对话意图识别 — 不同意图走不同的处理路径
 *
 * detect() 基于关键词匹配，覆盖快捷问题和常见表述。
 * 没命中的都走 NORMAL_CHAT（RAG 流程）。
 */
enum ChatIntent {

    HOT_ARTICLES,    // 热门文章推荐 → 查 Counter 浏览量排序
    LATEST_ARTICLES, // 最新文章（预留）
    NORMAL_CHAT;     // 普通 RAG 问答

    // 热门文章关键词：匹配这些就走 Counter 查询
    private static final String[] HOT_KEYWORDS = {
        "热门", "热文", "hot", "popular", "推荐文章", "推荐热门",
        "热门文章", "热门推荐", "最受欢迎"
    };

    // 最新文章关键词（预留，暂不走特殊逻辑）
    private static final String[] LATEST_KEYWORDS = {
        "最新文章", "最近文章", "最新发布"
    };

    /**
     * 从用户消息中检测意图 — 全部转小写匹配
     */
    static ChatIntent detect(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return NORMAL_CHAT;
        }
        String lower = userMessage.toLowerCase();
        for (String kw : HOT_KEYWORDS) {
            if (lower.contains(kw)) {
                return HOT_ARTICLES;
            }
        }
        for (String kw : LATEST_KEYWORDS) {
            if (lower.contains(kw)) {
                return LATEST_ARTICLES;
            }
        }
        return NORMAL_CHAT;
    }
}

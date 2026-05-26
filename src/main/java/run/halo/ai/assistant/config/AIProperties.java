package run.halo.ai.assistant.config;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 统一配置读取入口
 *
 * 直接从 ConfigMap 读取插件配置，不再依赖 ReactiveSettingFetcher。
 * ConfigMap 名: ai-assistant-configmap
 * data 字段: { "models": "{...json...}", "chunking": "{...}", ... }
 */
@Component
@Data
public class AIProperties {

    // ConfigMap 名称，与 plugin.yaml 中 configMapName 保持一致
    private static final String CONFIG_MAP_NAME = "ai-assistant-configmap";

    private final ReactiveExtensionClient client;

    public AIProperties(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 从 ConfigMap 读取指定 group 的 JSON 配置
     * 如果 ConfigMap 不存在或 group 为空，返回空的 JsonNode（解析方法会使用默认值）
     */
    private Mono<JsonNode> readGroup(String group) {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String json = data.get(group);
                if (json == null || json.isBlank()) return null;
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                } catch (Exception e) {
                    return null;
                }
            })
            .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.getInstance());
    }

    public Mono<ModelConfig> getModelConfig() {
        return readGroup("models").map(this::parseModelConfig);
    }

    public Mono<ChunkConfig> getChunkConfig() {
        return readGroup("chunking").map(this::parseChunkConfig);
    }

    public Mono<RetrievalConfig> getRetrievalConfig() {
        return readGroup("retrieval").map(this::parseRetrievalConfig);
    }

    public Mono<EnhancementConfig> getEnhancementConfig() {
        return readGroup("enhancement").map(this::parseEnhancementConfig);
    }

    public Mono<ChatConfig> getChatConfig() {
        return readGroup("chat").map(this::parseChatConfig);
    }

    // ===== 解析方法 =====

    private ModelConfig parseModelConfig(JsonNode node) {
        ModelConfig c = new ModelConfig();
        c.setChatBaseUrl(textVal(node, "chatBaseUrl", "https://api.deepseek.com/v1"));
        c.setChatApiKey(textVal(node, "chatApiKey", ""));
        c.setChatModel(textVal(node, "chatModel", "deepseek-chat"));
        c.setEmbeddingBaseUrl(textVal(node, "embeddingBaseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        c.setEmbeddingApiKey(textVal(node, "embeddingApiKey", ""));
        c.setEmbeddingModel(textVal(node, "embeddingModel", "text-embedding-v3"));
        c.setEmbeddingDimensions(intVal(node, "embeddingDimensions", 1024));
        c.setRerankEnabled(boolVal(node, "rerankEnabled", false));
        c.setRerankBaseUrl(textVal(node, "rerankBaseUrl", "https://api.siliconflow.cn/v1"));
        c.setRerankApiKey(textVal(node, "rerankApiKey", ""));
        c.setRerankModel(textVal(node, "rerankModel", "BAAI/bge-reranker-v2-m3"));
        c.setQueryRewriteEnabled(boolVal(node, "queryRewriteEnabled", false));
        c.setQueryRewriteBaseUrl(textVal(node, "queryRewriteBaseUrl", "https://open.bigmodel.cn/api/paas/v4/"));
        c.setQueryRewriteApiKey(textVal(node, "queryRewriteApiKey", ""));
        c.setQueryRewriteModel(textVal(node, "queryRewriteModel", "glm-4-flash"));
        return c;
    }

    private ChunkConfig parseChunkConfig(JsonNode node) {
        ChunkConfig c = new ChunkConfig();
        c.setChunkMode(textVal(node, "chunkMode", "auto"));
        c.setChunkSize(intVal(node, "chunkSize", 500));
        c.setChunkOverlap(intVal(node, "chunkOverlap", 50));
        c.setChunkSeparator(textVal(node, "chunkSeparator", "\n\n"));
        c.setMarkdownHeadingAware(boolVal(node, "markdownHeadingAware", true));
        c.setAutoKeywords(boolVal(node, "autoKeywords", false));
        c.setAutoKeywordsCount(intVal(node, "autoKeywordsCount", 3));
        c.setCleanWhitespace(boolVal(node, "cleanWhitespace", true));
        return c;
    }

    private RetrievalConfig parseRetrievalConfig(JsonNode node) {
        RetrievalConfig c = new RetrievalConfig();
        c.setSearchMode(textVal(node, "searchMode", "hybrid"));
        c.setSemanticWeight(floatVal(node, "semanticWeight", 0.7f));
        c.setTopK(intVal(node, "topK", 20));
        c.setSimilarityThreshold(floatVal(node, "similarityThreshold", 0.5f));
        c.setTopN(intVal(node, "topN", 5));
        c.setHighlightResults(boolVal(node, "highlightResults", false));
        c.setNoMatchBehavior(textVal(node, "noMatchBehavior", "continue"));
        c.setNoMatchReply(textVal(node, "noMatchReply", "抱歉，未在博客中找到与您问题相关的内容。"));
        return c;
    }

    private EnhancementConfig parseEnhancementConfig(JsonNode node) {
        EnhancementConfig c = new EnhancementConfig();
        c.setQueryRewriteToggle(boolVal(node, "queryRewriteToggle", false));
        c.setQueryRewritePrompt(textVal(node, "queryRewritePrompt", ""));
        c.setQueryRewriteWithHistory(boolVal(node, "queryRewriteWithHistory", true));
        c.setHydeEnabled(boolVal(node, "hydeEnabled", false));
        c.setHydePrompt(textVal(node, "hydePrompt", ""));
        c.setRerankToggle(boolVal(node, "rerankToggle", false));
        c.setRerankScoreThreshold(floatVal(node, "rerankScoreThreshold", 0.0f));
        c.setRerankTopN(intVal(node, "rerankTopN", 5));
        c.setCrossLanguageEnabled(boolVal(node, "crossLanguageEnabled", false));
        c.setCrossLanguageTargets(textVal(node, "crossLanguageTargets", "en"));
        c.setShowCitations(boolVal(node, "showCitations", true));
        return c;
    }

    private ChatConfig parseChatConfig(JsonNode node) {
        ChatConfig c = new ChatConfig();
        c.setSystemPrompt(textVal(node, "systemPrompt", ""));
        c.setTemperature(floatVal(node, "temperature", 0.7f));
        c.setMaxTokens(intVal(node, "maxTokens", 2048));
        c.setHistoryTurns(intVal(node, "historyTurns", 5));
        c.setStreamOutput(boolVal(node, "streamOutput", true));
        c.setWidgetPosition(textVal(node, "widgetPosition", "right-bottom"));
        c.setWidgetThemeColor(textVal(node, "widgetThemeColor", "#4F46E5"));
        c.setWidgetTheme(textVal(node, "widgetTheme", "auto"));
        c.setWelcomeMessage(textVal(node, "welcomeMessage", "Hi! 有什么想了解的？"));
        c.setShortcutQuestions(textVal(node, "shortcutQuestions", ""));
        c.setWidgetWidth(intVal(node, "widgetWidth", 400));
        c.setWidgetHeight(intVal(node, "widgetHeight", 600));
        c.setWidgetTriggerAlign(textVal(node, "widgetTriggerAlign", "auto"));
        c.setWidgetTriggerOffsetY(intVal(node, "widgetTriggerOffsetY", 24));
        c.setAllowGuest(boolVal(node, "allowGuest", true));
        return c;
    }

    // ===== JsonNode 安全取值工具 =====

    private static String textVal(JsonNode node, String key, String defaultVal) {
        if (node == null || node.isNull()) return defaultVal;
        JsonNode val = node.get(key);
        return (val != null && !val.isNull() && !val.asText().isEmpty()) ? val.asText() : defaultVal;
    }

    private static int intVal(JsonNode node, String key, int defaultVal) {
        if (node == null || node.isNull()) return defaultVal;
        JsonNode val = node.get(key);
        return (val != null && !val.isNull() && val.isNumber()) ? val.asInt() : defaultVal;
    }

    private static boolean boolVal(JsonNode node, String key, boolean defaultVal) {
        if (node == null || node.isNull()) return defaultVal;
        JsonNode val = node.get(key);
        return (val != null && !val.isNull() && val.isBoolean()) ? val.asBoolean() : defaultVal;
    }

    private static float floatVal(JsonNode node, String key, float defaultVal) {
        if (node == null || node.isNull()) return defaultVal;
        JsonNode val = node.get(key);
        return (val != null && !val.isNull() && val.isNumber()) ? (float) val.asDouble() : defaultVal;
    }

    // ===== 配置数据类 =====

    @Data
    public static class ModelConfig {
        private String chatBaseUrl;
        private String chatApiKey;
        private String chatModel;
        private String embeddingBaseUrl;
        private String embeddingApiKey;
        private String embeddingModel;
        private int embeddingDimensions;
        private boolean rerankEnabled;
        private String rerankBaseUrl;
        private String rerankApiKey;
        private String rerankModel;
        private boolean queryRewriteEnabled;
        private String queryRewriteBaseUrl;
        private String queryRewriteApiKey;
        private String queryRewriteModel;
    }

    @Data
    public static class ChunkConfig {
        private String chunkMode;
        private int chunkSize;
        private int chunkOverlap;
        private String chunkSeparator;
        private boolean markdownHeadingAware;
        private boolean autoKeywords;
        private int autoKeywordsCount;
        private boolean cleanWhitespace;
    }

    @Data
    public static class RetrievalConfig {
        private String searchMode;
        private float semanticWeight;
        private int topK;
        private float similarityThreshold;
        private int topN;
        private boolean highlightResults;
        private String noMatchBehavior;
        private String noMatchReply;
    }

    @Data
    public static class EnhancementConfig {
        private boolean queryRewriteToggle;
        private String queryRewritePrompt;
        private boolean queryRewriteWithHistory;
        private boolean hydeEnabled;
        private String hydePrompt;
        private boolean rerankToggle;
        private float rerankScoreThreshold;
        private int rerankTopN;
        private boolean crossLanguageEnabled;
        private String crossLanguageTargets;
        private boolean showCitations;
    }

    @Data
    public static class ChatConfig {
        private String systemPrompt;
        private float temperature;
        private int maxTokens;
        private int historyTurns;
        private boolean streamOutput;
        private String widgetPosition;
        private String widgetThemeColor;
        /** 深浅色模式：auto / light / dark */
        private String widgetTheme;
        private String welcomeMessage;
        /** 快捷问题（多行文本，每行一个），显示在欢迎语下方点击可直接发送 */
        private String shortcutQuestions;
        private int widgetWidth;
        private int widgetHeight;
        /** 悬浮按钮对齐策略：auto（优先自动对齐博客按钮组）/ manual（强制用 widgetTriggerOffsetY） */
        private String widgetTriggerAlign;
        /** 悬浮按钮距视口底部的像素偏移，默认 24；调高可避让博客自带的「回到顶部」等按钮 */
        private int widgetTriggerOffsetY;
        private boolean allowGuest;
    }
}

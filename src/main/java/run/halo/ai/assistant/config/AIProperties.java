package run.halo.ai.assistant.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.endpoint.UsageLimit.UsageLimitsConfig;
import run.halo.ai.assistant.endpoint.UsageLimit.VisitorRateLimitConfig;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // Secret 名称，专门存储 API Keys
    private static final String SECRET_NAME = "ai-assistant-api-keys";
    private static final String[] API_KEY_FIELDS = {
        "chatApiKey", "embeddingApiKey", "rerankApiKey", "queryRewriteApiKey", "writingApiKey"
    };

    /**
     * 从 ConfigMap 读取指定 group 的 JSON，并从 Secret 注入 API Keys
     */
    private Mono<JsonNode> readGroup(String group) {
        Mono<JsonNode> configJson = client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String json = data.get(group);
                if (json == null || json.isBlank()) return null;
                try {
                    return new ObjectMapper().readTree(json);
                } catch (Exception e) {
                    return null;
                }
            })
            .defaultIfEmpty(new ObjectMapper().createObjectNode());

        Mono<Map<String, String>> secretKeys = client.fetch(Secret.class, SECRET_NAME)
            .map(secret -> {
                var stringData = secret.getStringData();
                if (stringData == null) return Map.<String, String>of();
                return stringData;
            })
            .onErrorResume(e -> Mono.just(Map.of()))
            .defaultIfEmpty(Map.of());

        return Mono.zip(configJson, secretKeys)
            .map(tuple -> {
                JsonNode node = tuple.getT1();
                Map<String, String> keys = tuple.getT2();
                if (keys.isEmpty()) return node;

                // 将 Secret 中的 API key 注入到 JSON 中
                ObjectNode mutable = node.deepCopy();
                for (String field : API_KEY_FIELDS) {
                    String val = keys.get(field);
                    if (val != null && !val.isBlank()) {
                        mutable.put(field, val);
                    }
                }
                return (JsonNode) mutable;
            });
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

    public Mono<ExcerptConfig> getExcerptConfig() {
        return readGroup("excerpt").map(this::parseExcerptConfig);
    }

    public Mono<WritingConfig> getWritingConfig() {
        return readGroup("writing").map(this::parseWritingConfig);
    }

    public Mono<UsageLimitsConfig> getUsageLimitsConfig() {
        return readGroup("usageLimits").map(this::parseUsageLimitsConfig);
    }

    public Mono<VisitorRateLimitConfig> getVisitorRateLimitConfig() {
        return readGroup("usageLimits").map(this::parseVisitorRateLimitConfig);
    }

    // ===== 解析方法 =====

    private UsageLimitsConfig parseUsageLimitsConfig(JsonNode node) {
        boolean enabled = boolVal(node, "enabled", false);
        Map<String, Long> chatModelLimits = new LinkedHashMap<>();
        // 兼容老字段 "perModel" — 升级时旧 ConfigMap 数据自动迁移
        JsonNode limitsNode = node.path("chatModelLimits");
        if (!limitsNode.isObject()) limitsNode = node.path("perModel");
        if (limitsNode.isObject()) {
            limitsNode.fields().forEachRemaining(e -> {
                try {
                    chatModelLimits.put(e.getKey(), e.getValue().asLong(0L));
                } catch (Exception ignored) {}
            });
        }
        return new UsageLimitsConfig(enabled, chatModelLimits);
    }

    private VisitorRateLimitConfig parseVisitorRateLimitConfig(JsonNode node) {
        JsonNode v = node.path("visitor");
        if (!v.isObject()) return VisitorRateLimitConfig.empty();
        boolean enabled = boolVal(v, "enabled", false);
        int daily = (int) longVal(v, "dailyLimit", 0L);
        int hourly = (int) longVal(v, "hourlyLimit", 0L);
        List<String> whitelist = new java.util.ArrayList<>();
        JsonNode wl = v.path("whitelist");
        if (wl.isArray()) {
            wl.forEach(n -> {
                String s = n.asText("");
                if (!s.isBlank()) whitelist.add(s.trim());
            });
        }
        return new VisitorRateLimitConfig(enabled, daily, hourly, whitelist);
    }

    private static long longVal(JsonNode node, String key, long defaultVal) {
        if (node == null || node.isNull()) return defaultVal;
        JsonNode val = node.get(key);
        return (val != null && !val.isNull() && val.isNumber()) ? val.asLong() : defaultVal;
    }

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
        c.setMinChunkSize(intVal(node, "minChunkSize", 50));
        c.setSentenceAware(boolVal(node, "sentenceAware", true));
        c.setKeywordsMaxTokens(intVal(node, "keywordsMaxTokens", 1024));
        c.setKeywordsBatchSize(intVal(node, "keywordsBatchSize", 20));
        return c;
    }

    private RetrievalConfig parseRetrievalConfig(JsonNode node) {
        RetrievalConfig c = new RetrievalConfig();
        c.setSearchMode(textVal(node, "searchMode", "hybrid"));
        c.setSemanticWeight(floatVal(node, "semanticWeight", 0.7f));
        c.setTopK(intVal(node, "topK", 20));
        c.setSimilarityThreshold(floatVal(node, "similarityThreshold", 0.5f));
        c.setTopN(intVal(node, "topN", 5));
        c.setShowReferences(boolVal(node, "showReferences", false));
        c.setNoMatchBehavior(textVal(node, "noMatchBehavior", "continue"));
        c.setNoMatchReply(textVal(node, "noMatchReply", "抱歉，未在博客中找到与您问题相关的内容。"));
        return c;
    }

    private EnhancementConfig parseEnhancementConfig(JsonNode node) {
        EnhancementConfig c = new EnhancementConfig();
        c.setQueryRewriteToggle(boolVal(node, "queryRewriteToggle", false));
        c.setQueryRewritePrompt(textVal(node, "queryRewritePrompt", ""));
        c.setQueryRewriteWithHistory(boolVal(node, "queryRewriteWithHistory", true));
        c.setKeepOriginalQuery(boolVal(node, "keepOriginalQuery", false));
        c.setHydeEnabled(boolVal(node, "hydeEnabled", false));
        c.setHydePrompt(textVal(node, "hydePrompt", ""));
        c.setRerankToggle(boolVal(node, "rerankToggle", false));
        c.setRerankScoreThreshold(floatVal(node, "rerankScoreThreshold", 0.0f));
        c.setRerankTopN(intVal(node, "rerankTopN", 5));
        c.setCrossLanguageEnabled(boolVal(node, "crossLanguageEnabled", false));
        c.setCrossLanguageTargets(textVal(node, "crossLanguageTargets", "en"));
        c.setCrossLanguageMaxResults(intVal(node, "crossLanguageMaxResults", 5));
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
        c.setWidgetIcon(textVal(node, "widgetIcon", "ri-sparkling-2-line"));
        c.setWidgetTriggerSize(intVal(node, "widgetTriggerSize", 35));
        c.setWidgetTriggerLabel(textVal(node, "widgetTriggerLabel", ""));
        c.setWidgetTheme(textVal(node, "widgetTheme", "auto"));
        c.setWelcomeMessage(textVal(node, "welcomeMessage", "Hi! 有什么想了解的？"));
        c.setShortcutQuestions(textVal(node, "shortcutQuestions", ""));
        c.setWidgetWidth(intVal(node, "widgetWidth", 400));
        c.setWidgetHeight(intVal(node, "widgetHeight", 600));
        c.setWidgetTriggerAlign(textVal(node, "widgetTriggerAlign", "auto"));
        c.setWidgetTriggerOffsetY(intVal(node, "widgetTriggerOffsetY", 24));
        c.setAllowGuest(boolVal(node, "allowGuest", true));
        c.setShowPrivacyTip(boolVal(node, "showPrivacyTip", false));
        c.setShowRetrievalStatus(boolVal(node, "showRetrievalStatus", false));
        return c;
    }

    private ExcerptConfig parseExcerptConfig(JsonNode node) {
        ExcerptConfig c = new ExcerptConfig();
        c.setEnabled(boolVal(node, "enabled", false));
        c.setMaxLength(intVal(node, "maxLength", 160));
        c.setMaxInputLength(intVal(node, "maxInputLength", 3000));
        c.setTemperature(floatVal(node, "temperature", 0.3f));
        c.setMaxTokens(intVal(node, "maxTokens", 512));
        c.setPrompt(textVal(node, "prompt", ""));
        return c;
    }

    private WritingConfig parseWritingConfig(JsonNode node) {
        WritingConfig c = new WritingConfig();
        c.setEnabled(boolVal(node, "enabled", true));
        c.setWritingBaseUrl(textVal(node, "writingBaseUrl", ""));
        c.setWritingApiKey(textVal(node, "writingApiKey", ""));
        c.setWritingModel(textVal(node, "writingModel", ""));
        c.setOutlineTemperature(floatVal(node, "outlineTemperature", 0.3f));
        c.setOutlineSections(intVal(node, "outlineSections", 6));
        // 嵌套深度 1-3（封顶 3，深度 4+ LLM 准确率不可靠）；不合法回落 1
        int depth = intVal(node, "outlineDepth", 1);
        c.setOutlineDepth(depth >= 1 && depth <= 3 ? depth : 1);
        // 编号方式枚举：chinese / chinese-paren / arabic / roman / none
        String numbering = textVal(node, "outlineNumbering", "chinese");
        c.setOutlineNumbering(isValidNumbering(numbering) ? numbering : "chinese");
        c.setOutlineExtraPrompt(textVal(node, "outlineExtraPrompt", ""));
        c.setMaxInputLength(intVal(node, "maxInputLength", 6000));
        c.setMaxTokens(intVal(node, "maxTokens", 2048));
        return c;
    }

    private static boolean isValidNumbering(String n) {
        return "chinese".equals(n) || "chinese-paren".equals(n)
            || "arabic".equals(n) || "roman".equals(n) || "none".equals(n);
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
        private int minChunkSize;
        private boolean sentenceAware;
        private int keywordsMaxTokens;
        private int keywordsBatchSize;
    }

    @Data
    public static class RetrievalConfig {
        private String searchMode;
        private float semanticWeight;
        private int topK;
        private float similarityThreshold;
        private int topN;
        private boolean showReferences;
        private String noMatchBehavior;
        private String noMatchReply;
    }

    @Data
    public static class EnhancementConfig {
        private boolean queryRewriteToggle;
        private String queryRewritePrompt;
        private boolean queryRewriteWithHistory;
        private boolean keepOriginalQuery;
        private boolean hydeEnabled;
        private String hydePrompt;
        private boolean rerankToggle;
        private float rerankScoreThreshold;
        private int rerankTopN;
        private boolean crossLanguageEnabled;
        private String crossLanguageTargets;
        private int crossLanguageMaxResults;
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
        private String widgetIcon;
        private int widgetTriggerSize;
        private String widgetTriggerLabel;
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
        private boolean showPrivacyTip;
        private boolean showRetrievalStatus;
    }

    @Data
    public static class ExcerptConfig {
        /** 是否启用 AI 自动摘要（控制 Halo 扩展点是否走 LLM 路径） */
        private boolean enabled;
        /** 目标摘要最大字符数（中文字符），传给 LLM 的 prompt 约束 */
        private int maxLength;
        /** 送入 LLM 的原文最大字符数（避免长文耗尽 token） */
        private int maxInputLength;
        /** 摘要生成温度，越低越稳定，0.2-0.4 适合摘要 */
        private float temperature;
        /** LLM 输出 token 上限 */
        private int maxTokens;
        /** 自定义提示词，为空则使用默认中文摘要 prompt */
        private String prompt;
    }

    @Data
    public static class WritingConfig {
        /** AI 写作辅助总开关：false 时编辑器内的 AI 大纲按钮和选区气泡菜单全部不可用 */
        private boolean enabled;
        /** 独立写作模型 API 地址（为空则复用对话模型） */
        private String writingBaseUrl;
        /** 独立写作模型 API Key */
        private String writingApiKey;
        /** 独立写作模型名称 */
        private String writingModel;
        /** 大纲生成温度 */
        private float outlineTemperature;
        /** 大纲章节数量 */
        private int outlineSections;
        /**
         * 大纲嵌套深度 1-3：
         * 1 = 只有顶层章节（扁平 sections）
         * 2 = 顶层 + 二级子章节（sections[*].children）
         * 3 = 顶层 + 二级 + 三级（children[*].children）
         * 深度 4+ 封顶：实测 LLM 输出多层嵌套 JSON 准确率 < 60%，实用价值低
         */
        private int outlineDepth;
        /**
         * 大纲章节编号方式：
         * chinese 一、二、三、 / chinese-paren （一）（二）（三）/
         * arabic 1. 2. 3. / roman I. II. III. / none 不加编号
         */
        private String outlineNumbering;
        /** 大纲自定义追加约束 */
        private String outlineExtraPrompt;
        /** 单次输入字符上限 */
        private int maxInputLength;
        /** LLM 输出 token 上限 */
        private int maxTokens;
    }
}

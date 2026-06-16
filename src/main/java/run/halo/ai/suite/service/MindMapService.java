package run.halo.ai.suite.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 思维导图生成服务 — 从文章内容提取结构，生成 Markdown 大纲，缓存到 post annotation。
 *
 * <p>缓存策略：将生成的 markdown + 内容哈希一起存入 post annotation。
 * 文章内容变更后哈希不匹配，自动重新生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MindMapService {

    private final ReactiveExtensionClient extensionClient;
    private final PostContentService postContentService;
    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** annotation key — 存储思维导图 markdown 和内容哈希 */
    private static final String ANNOTATION_KEY = "ai-assistant.halo.run/mindmap";

    /**
     * LLM Prompt — 让模型输出 Markdown 嵌套大纲，直接喂给 markmap 渲染。
     *
     * markmap 原生支持 Markdown 标题层级，所以不需要结构化 JSON。
     */
    private static final String MINDMAP_PROMPT =
        "你是一个文章结构分析助手。请分析以下文章，提取其核心结构，输出一个 Markdown 格式的大纲。\n\n"
        + "要求：\n"
        + "1. 第一行是文章标题，用 # 开头\n"
        + "2. 使用 Markdown 标题层级组织节点，层级最多 %d 层（%s）\n"
        + "3. 每个节点用简洁的短语（不超过 15 字），不要完整句子\n"
        + "4. 覆盖文章的所有关键论点和核心信息\n"
        + "5. 直接输出 Markdown，不要加任何解释\n"
        + "%s\n"
        + "文章标题：%s\n\n文章内容：\n%s";

    /**
     * 获取思维导图 — 优先读缓存，缓存未命中或内容已变更则重新生成。
     *
     * @return MindMapResult(markdown, cached)
     */
    public Mono<MindMapResult> getOrGenerate(String postName) {
        return extensionClient.fetch(Post.class, postName)
            .switchIfEmpty(Mono.error(new IllegalStateException("文章不存在: " + postName)))
            .flatMap(post -> {
                // 1) 先取文章全文算 hash
                return postContentService.getReleaseContent(postName)
                    .flatMap(contentWrapper -> {
                        String raw = contentWrapper.getRaw();
                        if (raw == null || raw.isBlank()) {
                            raw = contentWrapper.getContent();
                        }
                        if (raw == null || raw.isBlank()) {
                            return Mono.error(new IllegalStateException("文章内容为空"));
                        }

                        String content = raw;
                        String currentHash = String.valueOf(content.hashCode());
                        String title = post.getSpec() != null && post.getSpec().getTitle() != null
                            ? post.getSpec().getTitle() : postName;

                        return aiProperties.getMindMapConfig()
                            .flatMap(cfg -> {
                                if (!cfg.isEnabled()) {
                                    return Mono.error(
                                        new IllegalStateException("思维导图功能未启用"));
                                }

                                int maxInput = cfg.getMaxInputLength();
                                String truncated = content.length() > maxInput
                                    ? content.substring(0, maxInput) + "\n...[内容已截断]"
                                    : content;

                                int maxDepth = Math.max(2, Math.min(4, cfg.getMaxDepth()));
                                String extraPrompt = cfg.getExtraPrompt() == null
                                    ? "" : cfg.getExtraPrompt().trim();
                                String configHash = buildConfigHash(cfg, maxDepth, extraPrompt);

                                // 2) 检查缓存：文章内容或生成配置变化都会自动重新生成
                                String cachedJson = getAnnotation(post);
                                if (cachedJson != null) {
                                    try {
                                        JsonNode cached = objectMapper.readTree(cachedJson);
                                        String cachedHash = cached.path("contentHash").asText("");
                                        String cachedConfigHash = cached.path("configHash").asText("");
                                        String cachedMarkdown = cached.path("markdown").asText("");
                                        boolean oldCacheWithoutConfigHash = cachedConfigHash.isBlank();
                                        if (!oldCacheWithoutConfigHash
                                            && currentHash.equals(cachedHash)
                                            && configHash.equals(cachedConfigHash)
                                            && !cachedMarkdown.isEmpty()) {
                                            log.debug("[MindMap] 缓存命中: post={}", postName);
                                            return Mono.just(new MindMapResult(cachedMarkdown, true));
                                        }
                                        log.debug("[MindMap] 缓存失效: post={}", postName);
                                    } catch (Exception e) {
                                        log.warn("[MindMap] 解析缓存失败: post={}, {}", postName, e.getMessage());
                                    }
                                }

                                // 3) 缓存未命中 — 调 LLM 生成
                                String prompt = buildPrompt(title, truncated, maxDepth, extraPrompt);

                                return aiProperties.getModelConfig()
                                    .flatMap(modelCfg -> {
                                        List<Map<String, String>> messages = List.of(
                                            Map.of("role", "user", "content", prompt)
                                        );
                                        return llmClient.chatInternal(
                                            modelCfg.getChatBaseUrl(),
                                            modelCfg.getChatApiKey(),
                                            modelCfg.getChatModel(),
                                            messages,
                                            cfg.getTemperature(),
                                            cfg.getMaxTokens(),
                                            null,
                                            UsageScenario.MINDMAP_GENERATE
                                        );
                                    })
                                    .flatMap(markdown -> {
                                        // 4) 保存缓存（重新 fetch 最新版本避免乐观锁冲突）
                                        return saveAnnotation(postName, markdown, currentHash, configHash)
                                            .thenReturn(new MindMapResult(markdown, false));
                                    });
                            });
                    });
            });
    }

    /**
     * 访客端只读取已生成且未过期的缓存，不触发 LLM 调用。
     */
    public Mono<MindMapResult> getCachedForPublic(String postName) {
        return extensionClient.fetch(Post.class, postName)
            .filter(this::isPublicPost)
            .switchIfEmpty(Mono.error(new IllegalStateException("文章不存在: " + postName)))
            .flatMap(post -> aiProperties.getMindMapConfig()
                .flatMap(cfg -> {
                    if (!cfg.isEnabled()) {
                        return Mono.error(new IllegalStateException("思维导图功能未启用"));
                    }
                    return inspectCache(post).flatMap(cache -> {
                        if (!cache.hasMindMap()) {
                            return Mono.error(new IllegalStateException("思维导图尚未生成"));
                        }
                        if (cache.stale()) {
                            return Mono.error(new IllegalStateException("思维导图待更新"));
                        }
                        return Mono.just(new MindMapResult(cache.markdown(), true));
                    });
                }));
    }

    private boolean isPublicPost(Post post) {
        return post != null
            && !post.isDeleted()
            && post.isPublished()
            && post.getSpec() != null
            && Boolean.TRUE.equals(post.getSpec().getPublish());
    }

    /**
     * 清除思维导图缓存 — 管理员用
     */
    public Mono<Void> clearCache(String postName) {
        return extensionClient.fetch(Post.class, postName)
            .flatMap(post -> {
                Map<String, String> annotations = ensureAnnotations(post);
                if (annotations.remove(ANNOTATION_KEY) != null) {
                    return extensionClient.update(post).then();
                }
                return Mono.empty();
            });
    }

    /**
     * 检查文章当前脑图缓存状态，不触发 LLM 生成。
     */
    public Mono<MindMapCacheStatus> inspectCache(Post post) {
        String postName = post.getMetadata() != null ? post.getMetadata().getName() : "";
        String cachedJson = getAnnotation(post);
        if (cachedJson == null || cachedJson.isBlank()) {
            return Mono.just(new MindMapCacheStatus(false, false, "", "", "not_generated"));
        }

        JsonNode cached;
        try {
            cached = objectMapper.readTree(cachedJson);
        } catch (Exception e) {
            return Mono.just(new MindMapCacheStatus(false, true, "", "", "invalid"));
        }

        String markdown = cached.path("markdown").asText("");
        String cachedHash = cached.path("contentHash").asText("");
        String cachedConfigHash = cached.path("configHash").asText("");
        if (markdown.isBlank()) {
            return Mono.just(new MindMapCacheStatus(false, true, "", "", "invalid"));
        }

        return postContentService.getReleaseContent(postName)
            .flatMap(contentWrapper -> aiProperties.getMindMapConfig().map(cfg -> {
                String raw = contentWrapper.getRaw();
                if (raw == null || raw.isBlank()) {
                    raw = contentWrapper.getContent();
                }
                String currentHash = raw == null ? "" : String.valueOf(raw.hashCode());
                int maxDepth = Math.max(2, Math.min(4, cfg.getMaxDepth()));
                String extraPrompt = cfg.getExtraPrompt() == null ? "" : cfg.getExtraPrompt().trim();
                String currentConfigHash = buildConfigHash(cfg, maxDepth, extraPrompt);
                boolean stale = !currentHash.equals(cachedHash)
                    || cachedConfigHash.isBlank()
                    || !currentConfigHash.equals(cachedConfigHash);
                return new MindMapCacheStatus(true, stale, markdown, cachedHash,
                    stale ? "stale" : "generated");
            }))
            .onErrorResume(e -> Mono.just(
                new MindMapCacheStatus(true, true, markdown, cachedHash, "stale")));
    }

    // ===== annotation 读写 =====

    private String getAnnotation(Post post) {
        if (post.getMetadata() == null) return null;
        Map<String, String> annotations = post.getMetadata().getAnnotations();
        if (annotations == null) return null;
        return annotations.get(ANNOTATION_KEY);
    }

    private Mono<Post> saveAnnotation(String postName, String markdown, String contentHash,
                                      String configHash) {
        // 重新 fetch 最新版本，避免乐观锁冲突
        // （clearCache 可能在中间更新了同一个 post 的 annotation）
        return extensionClient.fetch(Post.class, postName)
            .flatMap(post -> {
                try {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("markdown", markdown);
                    data.put("contentHash", contentHash);
                    data.put("configHash", configHash);
                    String json = objectMapper.writeValueAsString(data);

                    Map<String, String> annotations = ensureAnnotations(post);
                    annotations.put(ANNOTATION_KEY, json);
                    return extensionClient.update(post);
                } catch (JsonProcessingException e) {
                    log.error("[MindMap] 序列化缓存数据失败: {}", e.getMessage());
                    return Mono.just(post);
                }
            });
    }

    private String buildPrompt(String title, String content, int maxDepth, String extraPrompt) {
        String extra = extraPrompt == null || extraPrompt.isBlank()
            ? ""
            : "6. 额外生成要求：" + extraPrompt.trim() + "\n";
        return String.format(MINDMAP_PROMPT, maxDepth, headingPath(maxDepth), extra, title, content);
    }

    private String headingPath(int maxDepth) {
        return switch (maxDepth) {
            case 2 -> "# → ##";
            case 4 -> "# → ## → ### → ####";
            default -> "# → ## → ###";
        };
    }

    private String buildConfigHash(AIProperties.MindMapConfig cfg, int maxDepth,
                                   String extraPrompt) {
        String signature = cfg.getTemperature()
            + "|" + cfg.getMaxTokens()
            + "|" + cfg.getMaxInputLength()
            + "|" + maxDepth
            + "|" + extraPrompt;
        return String.valueOf(signature.hashCode());
    }

    private Map<String, String> ensureAnnotations(Post post) {
        MetadataOperator meta = post.getMetadata();
        if (meta == null) {
            Metadata newMeta = new Metadata();
            post.setMetadata(newMeta);
            meta = newMeta;
        }
        Map<String, String> annotations = meta.getAnnotations();
        if (annotations == null) {
            annotations = new LinkedHashMap<>();
            meta.setAnnotations(annotations);
        }
        return annotations;
    }

    /**
     * 思维导图结果
     *
     * @param markdown  Markdown 格式的大纲（markmap 可直接渲染）
     * @param cached    true 表示来自缓存，false 表示刚生成
     */
    public record MindMapResult(String markdown, boolean cached) {}

    public record MindMapCacheStatus(
        boolean hasMindMap,
        boolean stale,
        String markdown,
        String contentHash,
        String status
    ) {}
}

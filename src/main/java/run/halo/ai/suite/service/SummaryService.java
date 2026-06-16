package run.halo.ai.suite.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;
import java.util.Map;

/**
 * AI 摘要生成服务 —— 手动触发场景（控制台 API）。
 * <p>
 * 编辑器内「自动生成摘要」走 Halo 的 {@code ExcerptGenerator} 扩展点
 * （参见 {@code service.AIExcerptGenerator}），不在此服务范围内。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final ReactiveExtensionClient extensionClient;
    private final PostContentService postContentService;
    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    private static final String DEFAULT_SUMMARY_PROMPT =
        "你是一个博客文章摘要助手。请为以下文章生成一段简洁的摘要（150 字以内），\n"
        + "涵盖文章的核心内容和主要观点。直接输出摘要，不要加任何前缀或解释。\n\n"
        + "文章标题：%s\n\n文章内容：\n%s";

    /**
     * 为单篇文章生成摘要
     * <p>
     * LLM 失败时直接抛错（不返回占位文本），避免上层误把错误信息当作 excerpt 写入。
     */
    public Mono<String> generateSummary(String postName) {
        return extensionClient.fetch(Post.class, postName)
            .flatMap(post -> {
                String title = post.getSpec() != null ? post.getSpec().getTitle() : postName;
                return postContentService.getReleaseContent(postName)
                    .flatMap(contentWrapper -> {
                        String content = contentWrapper.getRaw();
                        if (content == null || content.isBlank()) {
                            content = contentWrapper.getContent();
                            if (content == null || content.isBlank()) {
                                return Mono.error(new IllegalStateException("文章内容为空"));
                            }
                        }

                        String truncated = content.length() > 3000
                            ? content.substring(0, 3000) + "\n...[内容已截断]"
                            : content;

                        String prompt = DEFAULT_SUMMARY_PROMPT.formatted(title, truncated);

                        return aiProperties.getModelConfig()
                            .flatMap(modelConfig ->
                                aiProperties.getChatConfig()
                                    .flatMap(chatConfig -> {
                                        List<Map<String, String>> messages = List.of(
                                            Map.of("role", "user", "content", prompt)
                                        );
                                        return llmClient.chatInternal(
                                            modelConfig.getChatBaseUrl(),
                                            modelConfig.getChatApiKey(),
                                            modelConfig.getChatModel(),
                                            messages,
                                            0.3f,
                                            512,
                                            null,
                                            UsageScenario.SUMMARY_GENERATE
                                        );
                                    })
                            );
                    });
            })
            .map(String::trim)
            .onErrorMap(e -> {
                log.error("[SummaryService] 生成摘要失败: {} - {}", postName, e.getMessage());
                return new RuntimeException("摘要生成失败: " + e.getMessage(), e);
            });
    }

    /**
     * 为单篇文章生成摘要并自动保存到 excerpt。
     * <p>
     * LLM 失败时不会写入脏数据（生成失败前的 excerpt 保持不变）。
     */
    public Mono<String> generateAndSave(String postName) {
        return extensionClient.fetch(Post.class, postName)
            .flatMap(post -> generateSummary(postName)
                .flatMap(summary -> {
                    var excerpt = new Post.Excerpt();
                    excerpt.setRaw(summary);
                    excerpt.setAutoGenerate(false);
                    post.getSpec().setExcerpt(excerpt);
                    return extensionClient.update(post).thenReturn(summary);
                })
            );
    }

    /**
     * 批量生成摘要并自动保存到文章 excerpt 字段
     */
    public Flux<Map<String, Object>> generateAndSaveAllSummaries() {
        return extensionClient.list(Post.class,
                post -> post.isPublished() && !post.isDeleted()
                    && post.getSpec() != null
                    && Boolean.TRUE.equals(post.getSpec().getPublish()),
                null)
            .concatMap(post -> {
                String postName = post.getMetadata().getName();
                String title = post.getSpec().getTitle() != null
                    ? post.getSpec().getTitle() : "";
                return generateSummary(postName)
                    .flatMap(summary -> {
                        var excerpt = new Post.Excerpt();
                        excerpt.setRaw(summary);
                        excerpt.setAutoGenerate(false);
                        post.getSpec().setExcerpt(excerpt);
                        return extensionClient.update(post)
                            .<Map<String, Object>>map(updated -> Map.of(
                                "postName", postName,
                                "title", title,
                                "summary", summary,
                                "saved", true
                            ))
                            .onErrorResume(e -> {
                                log.error("[SummaryService] 保存摘要失败: {} - {}",
                                    postName, e.getMessage());
                                return Mono.just(Map.<String, Object>of(
                                    "postName", postName,
                                    "title", title,
                                    "summary", summary,
                                    "saved", false,
                                    "error", e.getMessage()
                                ));
                            });
                    });
            });
    }
}

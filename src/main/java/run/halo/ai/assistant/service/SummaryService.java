package run.halo.ai.assistant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.llm.LlmClient;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 摘要生成服务
 *
 * 读取文章内容，调用 LLM 生成简洁摘要。
 * 可用于文章列表页的自动描述、SEO meta description 等。
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
     *
     * @param postName 文章 name
     * @return 摘要文本
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
                                return Mono.just("文章内容为空");
                            }
                        }

                        // 截取前 3000 字（太长浪费 token）
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
                                        return llmClient.chat(
                                            modelConfig.getChatBaseUrl(),
                                            modelConfig.getChatApiKey(),
                                            modelConfig.getChatModel(),
                                            messages,
                                            0.3f, // 低温度，摘要需要稳定
                                            512
                                        );
                                    })
                            );
                    });
            })
            .map(String::trim)
            .onErrorResume(e -> {
                log.error("[SummaryService] 生成摘要失败: {} - {}", postName, e.getMessage());
                return Mono.just("生成失败: " + e.getMessage());
            });
    }

    /**
     * 为所有已发布文章批量生成摘要
     *
     * @return 每个文章的结果流 {postName, title, summary}
     */
    public Flux<Map<String, String>> generateAllSummaries() {
        return extensionClient.list(Post.class,
                post -> post.isPublished() && !post.isDeleted()
                    && post.getSpec() != null
                    && Boolean.TRUE.equals(post.getSpec().getPublish()),
                null)
            .flatMap(post -> {
                String postName = post.getMetadata().getName();
                return generateSummary(postName)
                    .map(summary -> Map.of(
                        "postName", postName,
                        "title", post.getSpec().getTitle() != null ? post.getSpec().getTitle() : "",
                        "summary", summary
                    ));
            });
    }
}

package run.halo.ai.suite.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.config.AIProperties.ExcerptConfig;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;

/**
 * AI 摘要生成器 —— 接管 Halo 的「自动生成摘要」功能。
 * <p>
 * AI 开关开启时调用 LLM 生成摘要；关闭时退回到纯文本截取，
 * 保证不管开关状态摘要功能都能正常工作。
 */
@Slf4j
@Extension
@Component
@RequiredArgsConstructor
public class AIExcerptGenerator implements run.halo.app.content.ExcerptGenerator {

    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    private static final String DEFAULT_PROMPT_TEMPLATE =
        "你是一个博客文章摘要助手。请为以下文章生成一段简洁的摘要（%d 字以内），"
        + "涵盖文章的核心内容和主要观点。直接输出摘要正文，不要加任何前缀、标题或解释。\n\n"
        + "文章内容：\n%s";

    @Override
    public Mono<String> generate(Context context) {
        return aiProperties.getExcerptConfig()
            .flatMap(cfg -> {
                if (cfg.isEnabled()) {
                    return generateWithAI(context, cfg);
                }
                return Mono.just(fallbackTruncate(context, cfg));
            });
    }

    /**
     * 纯文本截取 —— AI 关闭时的兜底逻辑。
     * 去掉 HTML 标签、压缩空白，按配置 maxLength 截取。
     */
    String fallbackTruncate(Context context, ExcerptConfig cfg) {
        String content = pickContent(context);
        if (content.isEmpty()) {
            return "";
        }
        String plain = stripHtml(content);
        int maxLen = Math.max(cfg.getMaxLength(), 50);
        if (plain.length() <= maxLen) {
            return plain;
        }
        return plain.substring(0, maxLen) + "...";
    }

    private Mono<String> generateWithAI(Context context, ExcerptConfig cfg) {
        String content = pickContent(context);
        if (content.isEmpty()) {
            log.debug("[AIExcerptGenerator] 内容为空，跳过 LLM 调用");
            return Mono.just("");
        }
        String plain = stripHtml(content);
        int maxInput = Math.max(cfg.getMaxInputLength(), 500);
        String truncated = plain.length() > maxInput
            ? plain.substring(0, maxInput) + "\n...[内容已截断]"
            : plain;

        int targetLen = Math.min(Math.max(cfg.getMaxLength(), 50), 500);
        String prompt = buildPrompt(cfg, targetLen, truncated);

        return aiProperties.getModelConfig()
            .flatMap(modelCfg -> llmClient.chatInternal(
                modelCfg.getEffectiveChatModel(),
                List.of(Map.of("role", "user", "content", prompt)),
                cfg.getTemperature() > 0 ? cfg.getTemperature() : 0.3f,
                cfg.getMaxTokens() > 0 ? cfg.getMaxTokens() : 512,
                null,
                UsageScenario.SUMMARY_GENERATE
            ))
            .map(this::postProcess)
            .onErrorResume(e -> {
                log.warn("[AIExcerptGenerator] LLM 调用失败，退回纯文本截取: {}", e.getMessage());
                return Mono.just(fallbackTruncate(context, cfg));
            });
    }

    private String buildPrompt(ExcerptConfig cfg, int targetLen, String content) {
        String custom = cfg.getPrompt();
        if (custom != null && !custom.isBlank()) {
            return custom
                .replace("{maxLength}", String.valueOf(targetLen))
                .replace("{content}", content);
        }
        return String.format(DEFAULT_PROMPT_TEMPLATE, targetLen, content);
    }

    /**
     * 清理 LLM 输出：去前后空白，去掉可能的前缀（如「摘要：」「标题：」）。
     */
    private String postProcess(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 去掉常见前缀
        for (String prefix : List.of("摘要：", "摘要:", "摘要 ", "Title：", "Title:")) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
                break;
            }
        }
        // 去掉首尾成对引号
        if ((s.startsWith("\"") && s.endsWith("\""))
            || (s.startsWith("「") && s.endsWith("」"))
            || (s.startsWith("『") && s.endsWith("』"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String pickContent(Context context) {
        String content = context.getContent();
        if (content == null || content.isBlank()) {
            content = context.getRaw();
        }
        return content == null ? "" : content;
    }

    /**
     * HTML 标签剥离 + 空白压缩。LLM 对纯文本响应更稳定，token 也更省。
     */
    private static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String plain = html
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ");
        plain = plain
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
        return plain.replaceAll("\\s+", " ").trim();
    }
}

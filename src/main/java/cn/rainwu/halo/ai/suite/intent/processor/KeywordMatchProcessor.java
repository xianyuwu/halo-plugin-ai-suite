package cn.rainwu.halo.ai.suite.intent.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.intent.PipelineProcessor;
import cn.rainwu.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.app.core.extension.content.Post;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 关键词文本匹配处理器 — 按 Post.spec.title/content 文本包含过滤.
 * <p>
 * 关键词来源：
 * <ul>
 *   <li>{@code mode=from_query}（默认）— 直接用用户原始问题作为关键词（简单 includes 过滤）</li>
 *   <li>{@code mode=fixed} — 用 {@code keyword} 参数里配置的固定关键词（逗号分隔，任一命中即保留）</li>
 * </ul>
 * <p>
 * 字段范围（params.fields，逗号分隔）：
 * <ul>
 *   <li>{@code title}（默认）— 只匹配标题</li>
 *   <li>{@code content} — 匹配标题 + 摘要（content 较重，首版只取 excerpt）</li>
 * </ul>
 * <p>
 * 注意：此处理器是纯字符串 includes 匹配（不语义），仅适合精确关键词场景.
 * 主题相关（如同义词、近义词）应使用 LLM_TITLE_FILTER.
 */
@Slf4j
@Component
public class KeywordMatchProcessor implements PipelineProcessor {

    public static final String TYPE = "KEYWORD_MATCH";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mono<List<Post>> process(List<Post> candidates, PipelineStep step,
                                    String userQuery, List<Map<String, String>> history) {
        String mode = param(step, "mode", "from_query");
        String fields = param(step, "fields", "title");
        boolean includeContent = fields.toLowerCase(Locale.ROOT).contains("content");

        List<String> keywords;
        if ("fixed".equalsIgnoreCase(mode)) {
            String kw = param(step, "keyword", "");
            keywords = split(kw);
        } else {
            // from_query：把用户问题切成关键词（按空格/标点切，过滤太短的）
            keywords = split(userQuery);
        }
        if (keywords.isEmpty()) {
            log.debug("[KeywordMatchProcessor] 无有效关键词，跳过过滤");
            return Mono.just(candidates);
        }

        List<String> finalKeywords = keywords;
        List<Post> filtered = candidates.stream()
            .filter(p -> {
                String title = p.getSpec() != null && p.getSpec().getTitle() != null
                    ? p.getSpec().getTitle().toLowerCase(Locale.ROOT) : "";
                String excerpt = includeContent && p.getSpec() != null
                    && p.getSpec().getExcerpt() != null
                    && p.getSpec().getExcerpt().getRaw() != null
                    ? p.getSpec().getExcerpt().getRaw().toLowerCase(Locale.ROOT) : "";
                String haystack = includeContent ? title + " " + excerpt : title;
                if (haystack.isBlank()) return false;
                for (String kw : finalKeywords) {
                    if (haystack.contains(kw)) return true;
                }
                return false;
            })
            .collect(Collectors.toList());

        log.debug("[KeywordMatchProcessor] {} → {} 条 (mode={}, keywords={})",
            candidates.size(), filtered.size(), mode, finalKeywords);
        return Mono.just(filtered);
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[\\s,，。.;；、?？!！]+"))
            .map(String::trim)
            .filter(s -> s.length() >= 2)   // 过滤单字（噪声大）
            .distinct()
            .collect(Collectors.toList());
    }
}

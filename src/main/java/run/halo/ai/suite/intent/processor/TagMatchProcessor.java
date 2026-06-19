package run.halo.ai.suite.intent.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.intent.PipelineProcessor;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签匹配处理器 — 按 Post.spec.tags 过滤.
 * <p>
 * Halo 的 {@code Post.spec.tags} 存的是 Tag 资源的 {@code metadata.name}（如
 * {@code tag-xyz}），不是展示名。所以本处理器需要：
 * <ol>
 *   <li>listAll(Tag.class) 建立 {@code displayName/slug → metadata.name} 映射</li>
 *   <li>把用户输入或配置里的标签词解析成 metadata.name 集合</li>
 *   <li>过滤 Post.spec.tags 与该集合有交集的文章</li>
 * </ol>
 * <p>
 * params:
 * <ul>
 *   <li>{@code mode=from_query}（默认）— 从用户问题提取标签词（简单切分）</li>
 *   <li>{@code mode=fixed} — 用 {@code tags} 参数配置的固定标签（逗号分隔）</li>
 *   <li>{@code tags} — fixed 模式下的标签列表（可以是 displayName / slug / metadata.name 三者之一）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagMatchProcessor implements PipelineProcessor {

    public static final String TYPE = "TAG_MATCH";

    private final ReactiveExtensionClient client;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mono<List<Post>> process(List<Post> candidates, PipelineStep step,
                                    String userQuery, List<Map<String, String>> history) {
        String mode = param(step, "mode", "from_query");

        return client.list(Tag.class, t -> true, null)
            .collectList()
            .map(tags -> {
                // 建立 displayName/slug/name（全小写）→ metadata.name 的查找表
                Map<String, String> lookup = new HashMap<>();
                for (Tag t : tags) {
                    String name = t.getMetadata() != null ? t.getMetadata().getName() : "";
                    if (name.isEmpty() || t.getSpec() == null) continue;
                    putIfPresent(lookup, t.getSpec().getDisplayName(), name);
                    putIfPresent(lookup, t.getSpec().getSlug(), name);
                    putIfPresent(lookup, name, name);
                }

                List<String> inputWords;
                if ("fixed".equalsIgnoreCase(mode)) {
                    inputWords = split(param(step, "tags", ""));
                } else {
                    inputWords = split(userQuery);
                }

                Set<String> targetNames = new LinkedHashSet<>();
                for (String w : inputWords) {
                    String resolved = lookup.get(w.toLowerCase(Locale.ROOT));
                    if (resolved != null) targetNames.add(resolved);
                }
                // 中文问句通常没有空格；遍历已有标签做包含匹配，
                // 例如“有哪些AI标签文章”也能提取出“AI”。
                if (!"fixed".equalsIgnoreCase(mode)) {
                    String normalizedQuery = userQuery == null
                        ? "" : userQuery.toLowerCase(Locale.ROOT);
                    for (Map.Entry<String, String> entry : lookup.entrySet()) {
                        if (entry.getKey().length() >= 2 && normalizedQuery.contains(entry.getKey())) {
                            targetNames.add(entry.getValue());
                        }
                    }
                }
                if (targetNames.isEmpty()) {
                    log.debug("[TagMatchProcessor] 未匹配到任何标签，返回空结果 (input={})", inputWords);
                    return List.of();
                }

                List<Post> filtered = candidates.stream()
                    .filter(p -> {
                        if (p.getSpec() == null || p.getSpec().getTags() == null) return false;
                        for (String pt : p.getSpec().getTags()) {
                            if (targetNames.contains(pt)) return true;
                        }
                        return false;
                    })
                    .collect(Collectors.toList());

                log.debug("[TagMatchProcessor] {} → {} 条 (targetNames={}, input={})",
                    candidates.size(), filtered.size(), targetNames, inputWords);
                return filtered;
            });
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (key != null && !key.isBlank()) {
            map.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
        }
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[\\s,，、]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .collect(Collectors.toList());
    }
}

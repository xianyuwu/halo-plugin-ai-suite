package run.halo.ai.suite.intent.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.intent.PipelineProcessor;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分类匹配处理器 — 按 Post.spec.categories 过滤.
 * <p>
 * 与 {@link TagMatchProcessor} 同构：Halo 的 {@code Post.spec.categories}
 * 存的是 Category 资源的 {@code metadata.name}，需要先建立
 * {@code displayName/slug → metadata.name} 映射再匹配.
 * <p>
 * params:
 * <ul>
 *   <li>{@code mode=from_query}（默认）— 从用户问题中提取分类</li>
 *   <li>{@code mode=fixed} — 使用 {@code categories} 固定分类列表</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryMatchProcessor implements PipelineProcessor {

    public static final String TYPE = "CATEGORY_MATCH";

    private final ReactiveExtensionClient client;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mono<List<Post>> process(List<Post> candidates, PipelineStep step,
                                    String userQuery, List<Map<String, String>> history) {
        String mode = param(step, "mode", "from_query");
        List<String> inputWords = "fixed".equalsIgnoreCase(mode)
            ? split(param(step, "categories", "")) : split(userQuery);

        return client.list(Category.class, c -> true, null)
            .collectList()
            .map(categories -> {
                Map<String, String> lookup = new HashMap<>();
                for (Category c : categories) {
                    String name = c.getMetadata() != null ? c.getMetadata().getName() : "";
                    if (name.isEmpty() || c.getSpec() == null) continue;
                    putIfPresent(lookup, c.getSpec().getDisplayName(), name);
                    putIfPresent(lookup, c.getSpec().getSlug(), name);
                    putIfPresent(lookup, name, name);
                }

                Set<String> targetNames = new LinkedHashSet<>();
                for (String w : inputWords) {
                    String resolved = lookup.get(w.toLowerCase(Locale.ROOT));
                    if (resolved != null) targetNames.add(resolved);
                }
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
                    log.debug("[CategoryMatchProcessor] 未匹配到分类 (input={})", inputWords);
                    return List.of();
                }

                List<Post> filtered = candidates.stream()
                    .filter(p -> {
                        if (p.getSpec() == null || p.getSpec().getCategories() == null) return false;
                        for (String pc : p.getSpec().getCategories()) {
                            if (targetNames.contains(pc)) return true;
                        }
                        return false;
                    })
                    .collect(Collectors.toList());

                log.debug("[CategoryMatchProcessor] {} → {} 条 (targetNames={})",
                    candidates.size(), filtered.size(), targetNames);
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

package cn.rainwu.halo.ai.suite.intent.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.intent.PipelineProcessor;
import cn.rainwu.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览量排序处理器 — 按 Halo Counter 浏览量降序排列.
 * <p>
 * 迁移自原硬编码 {@code ChatService.fetchHotArticles} 的核心逻辑：
 * Counter 命名格式 {@code posts.content.halo.run/{postName}}，逐个反查 visits 后排序.
 * <p>
 * params:
 * <ul>
 *   <li>{@code limit} — 截取条数，默认 10</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitSortProcessor implements PipelineProcessor {

    public static final String TYPE = "VISIT_SORT";

    private final ReactiveExtensionClient client;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mono<List<Post>> process(List<Post> candidates, PipelineStep step,
                                    String userQuery, List<Map<String, String>> history) {
        int limit = paramInt(step, "limit", 10);
        if (candidates.isEmpty()) {
            return Mono.just(candidates);
        }

        // 并发查每个 Post 的 Counter，组装 (post, visits) 后排序截取
        return client.list(Counter.class, c -> true, null)
            .collectMap(
                c -> c.getMetadata() != null ? c.getMetadata().getName() : "",
                c -> c.getVisit() != null ? c.getVisit() : 0)
            .map(counterMap -> {
                Map<String, Integer> visitByName = new HashMap<>();
                for (Map.Entry<String, Integer> e : counterMap.entrySet()) {
                    // Counter name 格式 posts.content.halo.run/{postName}，取 postName
                    String name = e.getKey();
                    int slash = name.indexOf('/');
                    if (slash > 0) {
                        visitByName.put(name.substring(slash + 1), e.getValue());
                    }
                }
                List<Post> sorted = new java.util.ArrayList<>(candidates);
                sorted.sort(Comparator.<Post, Integer>comparing(
                    p -> {
                        String pn = p.getMetadata() != null ? p.getMetadata().getName() : "";
                        return visitByName.getOrDefault(pn, 0);
                    },
                    Comparator.reverseOrder()));
                if (limit > 0 && sorted.size() > limit) {
                    sorted = new java.util.ArrayList<>(sorted.subList(0, limit));
                }
                log.debug("[VisitSortProcessor] {} → {} 条 (limit={})",
                    candidates.size(), sorted.size(), limit);
                return sorted;
            });
    }
}

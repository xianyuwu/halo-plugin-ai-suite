package run.halo.ai.suite.intent.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.intent.PipelineProcessor;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.app.core.extension.content.Post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 时间排序处理器 — 按 Post.spec.publishTime 排序.
 * <p>
 * 排序范式参考 {@code ConsoleMindMapEndpoint.publishTimeComparator}：
 * publishTime 为 null 的排到末尾（不论 asc/desc 都视为最旧）.
 * <p>
 * params:
 * <ul>
 *   <li>{@code order} — "desc"（默认，最新在前）或 "asc"</li>
 *   <li>{@code limit} — 截取条数，默认 0 表示不截取</li>
 * </ul>
 */
@Slf4j
@Component
public class TimeSortProcessor implements PipelineProcessor {

    public static final String TYPE = "TIME_SORT";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mono<List<Post>> process(List<Post> candidates, PipelineStep step,
                                    String userQuery, List<Map<String, String>> history) {
        boolean asc = "asc".equalsIgnoreCase(param(step, "order", "desc"));
        int limit = paramInt(step, "limit", 0);

        return Mono.fromCallable(() -> {
            List<Post> sorted = new ArrayList<>(candidates);
            sorted.sort(publishTimeComparator(asc));
            if (limit > 0 && sorted.size() > limit) {
                sorted = new ArrayList<>(sorted.subList(0, limit));
            }
            log.debug("[TimeSortProcessor] {} → {} 条 (asc={}, limit={})",
                candidates.size(), sorted.size(), asc, limit);
            return sorted;
        });
    }

    /**
     * publishTime null-safe 比较器 — null 视为最旧，永远排到末尾.
     * <p>范式对齐 {@code ConsoleMindMapEndpoint.java:102-109}，保持整个项目排序行为一致.
     */
    public static Comparator<Post> publishTimeComparator(boolean asc) {
        return (a, b) -> {
            Instant ta = a.getSpec() != null ? a.getSpec().getPublishTime() : null;
            Instant tb = b.getSpec() != null ? b.getSpec().getPublishTime() : null;
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;   // a 较旧 → 末尾
            if (tb == null) return -1;  // b 较旧 → 末尾
            return asc ? ta.compareTo(tb) : tb.compareTo(ta);
        };
    }
}

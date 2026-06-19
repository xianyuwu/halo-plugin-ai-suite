package run.halo.ai.suite.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.ai.suite.extension.IntentRoute;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.ai.suite.intent.PostQuerySupport;
import run.halo.ai.suite.intent.PipelineProcessor;
import run.halo.ai.suite.rag.PipelineTrace;
import run.halo.app.core.extension.content.Post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * Pipeline 编排器 — 按顺序执行 IntentRoute 配置的处理器链.
 * <p>
 * 执行流程：
 * <ol>
 *   <li>从 {@link PostQuerySupport} 拿到所有已发布文章作为初始 candidates</li>
 *   <li>依次调用每个 {@link PipelineStep} 对应的 {@link PipelineProcessor}</li>
 *   <li>把上一步输出传给下一步，逐步过滤/排序</li>
 *   <li>每步记录到 {@link PipelineTrace}（可空，trace=null 时零开销）</li>
 * </ol>
 * <p>
 * 容错策略：单个 step 失败时默认返回空结果，避免把未过滤的全量文章
 * 伪装成命中结果。可显式设置 {@code onFailure=keep} 保留上一步结果。
 */
@Slf4j
@Component
public class PipelineExecutor {

    private final PostQuerySupport postQuerySupport;
    private final Map<String, PipelineProcessor> processors;

    /**
     * Spring 自动注入所有 PipelineProcessor 实现，按 {@link PipelineProcessor#type()} 索引.
     */
    public PipelineExecutor(PostQuerySupport postQuerySupport,
                            List<PipelineProcessor> processorList) {
        this.postQuerySupport = postQuerySupport;
        this.processors = new LinkedHashMap<>();
        for (PipelineProcessor p : processorList) {
            this.processors.put(p.type(), p);
        }
        log.info("[PipelineExecutor] 已加载处理器: {}", processors.keySet());
    }

    /**
     * 执行意图 pipeline.
     *
     * @param route   命中的意图配置
     * @param query   用户原始问题
     * @param history 对话历史
     * @param trace   可空的追踪记录器
     * @return 处理后的 Post 列表（可能为空，表示该意图无结果）
     */
    public Mono<List<Post>> execute(IntentRoute route, String query,
                                    List<Map<String, String>> history, PipelineTrace trace) {
        List<PipelineStep> steps = route.getSpec() != null ? route.getSpec().getPipeline() : null;
        if (steps == null || steps.isEmpty()) {
            if (trace != null) {
                trace.addSkipped("pipeline", "Pipeline",
                    "意图 " + routeId(route) + " 无处理器步骤");
            }
            return Mono.just(List.of());
        }

        // 第一步输入：所有已发布文章
        long fetchStart = trace != null ? System.currentTimeMillis() : 0;
        return postQuerySupport.listPublishedPublicPosts()
            .flatMap(initial -> {
                if (trace != null) {
                    trace.addStage("fetch_posts", "加载文章", fetchStart,
                        System.currentTimeMillis(),
                        "ok", "完成", "共 " + initial.size() + " 篇已发布文章",
                        Map.of("totalPosts", initial.size()));
                }
                return runSteps(route, steps, initial, query, history, trace);
            });
    }

    private Mono<List<Post>> runSteps(IntentRoute route, List<PipelineStep> steps,
                                      List<Post> initial, String query,
                                      List<Map<String, String>> history, PipelineTrace trace) {
        // 串行执行：每步用上一步结果，用 reduce 风格的 flatMap 链
        Mono<List<Post>> chain = Mono.just(initial);
        for (int i = 0; i < steps.size(); i++) {
            PipelineStep step = steps.get(i);
            final int stepIdx = i;
            chain = chain.flatMap(candidates -> runStep(route, step, stepIdx, candidates,
                query, history, trace));
        }
        return chain;
    }

    private Mono<List<Post>> runStep(IntentRoute route, PipelineStep step, int stepIdx,
                                     List<Post> candidates, String query,
                                     List<Map<String, String>> history, PipelineTrace trace) {
        String type = step.getType() != null ? step.getType() : "";
        PipelineProcessor processor = processors.get(type);
        if (processor == null) {
            log.warn("[PipelineExecutor] 未知处理器类型: {} (意图 {})", type, routeId(route));
            if (trace != null) {
                trace.addStage("pipeline_step_" + stepIdx, "步骤 " + (stepIdx + 1) + ": " + type,
                    System.currentTimeMillis(), System.currentTimeMillis(),
                    "fallback", "降级", "未知处理器类型 " + type + "，跳过",
                    Map.of("type", type, "unknown", true));
            }
            return Mono.just(candidates);
        }

        long start = trace != null ? System.currentTimeMillis() : 0;
        return processor.process(candidates, step, query, history)
            .map(result -> {
                if (trace != null) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", type);
                    data.put("in", candidates.size());
                    data.put("out", result.size());
                    data.put("params", step.getParams());
                    // posts: 结构化输出（标题+链接+发布时间），供试跑预览渲染.
                    // 同时保留 titles 字符串列表做向后兼容（老 debug trace 消费方）.
                    data.put("titles", result.stream()
                        .limit(20)
                        .map(p -> p.getSpec() != null ? p.getSpec().getTitle() : "?")
                        .toList());
                    data.put("posts", result.stream()
                        .limit(20)
                        .map(p -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("title", p.getSpec() != null && p.getSpec().getTitle() != null
                                ? p.getSpec().getTitle() : "");
                            m.put("url", p.getStatus() != null && p.getStatus().getPermalink() != null
                                ? p.getStatus().getPermalink() : "");
                            m.put("publishTime", p.getSpec() != null && p.getSpec().getPublishTime() != null
                                ? p.getSpec().getPublishTime().toString().substring(0, 10) : "");
                            return m;
                        })
                        .toList());
                    String status = result.isEmpty() && !candidates.isEmpty()
                        ? "fallback" : "ok";
                    String statusLabel = result.isEmpty() && !candidates.isEmpty()
                        ? "全过滤" : "完成";
                    trace.addStage("pipeline_step_" + stepIdx,
                        "步骤 " + (stepIdx + 1) + ": " + type,
                        start, System.currentTimeMillis(),
                        status, statusLabel,
                        candidates.size() + " → " + result.size() + " 篇", data);
                }
                return result;
            })
            .onErrorResume(e -> {
                String failurePolicy = processor.param(step, "onFailure", "empty");
                boolean keep = "keep".equalsIgnoreCase(failurePolicy);
                log.warn("[PipelineExecutor] 步骤 {} ({}) 失败，策略={}: {}",
                    stepIdx + 1, type, keep ? "keep" : "empty", e.getMessage());
                if (trace != null) {
                    trace.addStage("pipeline_step_" + stepIdx,
                        "步骤 " + (stepIdx + 1) + ": " + type,
                        start, System.currentTimeMillis(),
                        "fallback", "降级",
                        "失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Map.of("type", type, "error", String.valueOf(e.getMessage()),
                            "failurePolicy", keep ? "keep" : "empty"));
                }
                return Mono.just(keep ? candidates : List.of());
            });
    }

    private static String routeId(IntentRoute route) {
        return route.getMetadata() != null ? route.getMetadata().getName() : "?";
    }
}

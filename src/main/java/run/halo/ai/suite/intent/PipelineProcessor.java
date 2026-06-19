package run.halo.ai.suite.intent;

import reactor.core.publisher.Mono;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.app.core.extension.content.Post;

import java.util.List;
import java.util.Map;

/**
 * Pipeline 处理器接口 — 一个意图配置的 pipeline 由若干 PipelineProcessor 串联组成.
 * <p>
 * 执行语义：{@link PipelineExecutor} 把上一步的候选 Post 列表传给下一步，
 * 每步可以过滤（减少）或排序（重排）候选列表。第一步的输入是博客所有已发布文章。
 * <p>
 * 实现要点：
 * <ul>
 *   <li>必须是 Spring {@code @Component}，由 {@link PipelineExecutor} 按 {@link #type()} 自动注入匹配</li>
 *   <li>{@code process} 必须对失败容错（onError 返回原 candidates，不中断整条 pipeline）</li>
 *   <li>纯 IO 操作（如读 Post）应切到 {@code boundedElastic}，避免阻塞 reactor 线程</li>
 * </ul>
 */
public interface PipelineProcessor {

    /**
     * 处理器类型标识 — 与 {@link PipelineStep#getType()} 对应，全大写下划线命名.
     * <p>已注册类型：
     * <ul>
     *   <li>{@code TOPIC_MATCH} — 标签/分类强匹配与 LLM 多字段语义匹配并集</li>
     *   <li>{@code LLM_TITLE_FILTER} — LLM 推理文章标题与查询主题相关性</li>
     *   <li>{@code TAG_MATCH} — 按 Post.spec.tags 匹配</li>
     *   <li>{@code KEYWORD_MATCH} — 按 title/content 文本包含过滤</li>
     *   <li>{@code CATEGORY_MATCH} — 按 Post.spec.categories 匹配</li>
     *   <li>{@code TIME_SORT} — 按 publishTime 排序</li>
     *   <li>{@code VISIT_SORT} — 按 Counter 浏览量排序</li>
     * </ul>
     */
    String type();

    /**
     * 执行处理.
     *
     * @param candidates 上一步输出的候选 Post 列表（第一步时是所有已发布文章）
     * @param step       本步骤配置（含 params）
     * @param userQuery  用户原始问题（供 LLM/提取类处理器使用）
     * @param history    对话历史（仅 LLM 类处理器可能需要）
     * @return 处理后的 Post 列表（已过滤/已排序）
     */
    Mono<List<Post>> process(List<Post> candidates, PipelineStep step,
                             String userQuery, List<Map<String, String>> history);

    /**
     * 默认参数读取工具 — 从 step.params 取值，缺失返回默认.
     */
    default String param(PipelineStep step, String key, String defaultValue) {
        if (step == null || step.getParams() == null) return defaultValue;
        String v = step.getParams().get(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    /**
     * 默认参数读取工具 — 整数类型.
     */
    default int paramInt(PipelineStep step, String key, int defaultValue) {
        String v = param(step, key, null);
        if (v == null) return defaultValue;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

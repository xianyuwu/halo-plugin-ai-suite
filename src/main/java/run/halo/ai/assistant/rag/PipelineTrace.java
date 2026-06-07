package run.halo.ai.assistant.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 管线调试追踪 — 记录每个阶段的输入输出、耗时和状态
 *
 * 用法：trace == null 时所有记录操作为空操作，非 debug 模式零开销
 */
public class PipelineTrace {

    /** 单个阶段的追踪记录 */
    public record TraceStage(
        String name,         // 机器标识：query_rewrite, embed, hybrid_retrieve ...
        String label,        // 中文显示：查询改写, 向量编码, 混合检索 ...
        long startedAt,      // 开始时间（epoch millis）
        long finishedAt,     // 结束时间（epoch millis，进行中为 0）
        String status,       // ok | fallback | skipped | error
        String statusLabel,  // 完成 | 降级 | 跳过 | 异常
        String detail,       // 人类可读描述，如 "'什么是Docker' → 'Docker安装配置'"
        Object data          // 可 JSON 序列化的附加数据（文档列表、维度等）
    ) {
        public long durationMs() {
            return finishedAt > startedAt ? finishedAt - startedAt : 0;
        }
    }

    private final String query;
    private final String intent;
    private final List<TraceStage> stages;

    public PipelineTrace(String query, String intent) {
        this.query = query;
        this.intent = intent;
        this.stages = new ArrayList<>();
    }

    public String query() { return query; }
    public String intent() { return intent; }
    public List<TraceStage> stages() { return stages; }

    /** 添加一个已完成的阶段 */
    public void addStage(String name, String label, long startedAt, long finishedAt,
                         String status, String statusLabel, String detail, Object data) {
        stages.add(new TraceStage(name, label, startedAt, finishedAt,
            status, statusLabel, detail, data));
    }

    /** 添加一个跳过的阶段（起止时间相同） */
    public void addSkipped(String name, String label, String detail) {
        long now = System.currentTimeMillis();
        stages.add(new TraceStage(name, label, now, now,
            "skipped", "跳过", detail, null));
    }

    /** 计算总耗时（第一个阶段开始 → 最后一个阶段结束） */
    public long totalDurationMs() {
        if (stages.isEmpty()) return 0;
        long start = stages.get(0).startedAt();
        long end = stages.get(stages.size() - 1).finishedAt();
        return end > start ? end - start : 0;
    }
}

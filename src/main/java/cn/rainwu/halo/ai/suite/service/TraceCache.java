package cn.rainwu.halo.ai.suite.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.rainwu.halo.ai.suite.rag.PipelineTrace;

/**
 * 管线 Trace 内存缓存 — 按 logId 暂存最近一次问答的完整 trace.
 *
 * <p>设计：问答完成时写入，用户点踩时读出并写入 ChatLog，然后移除。
 * 未被点踩的 trace 会在 30 分钟后自动淘汰，零存储成本。
 *
 * <p>线程安全：LinkedHashMap + synchronized，问答量不大（日均几百），
 * 锁粒度可接受。
 */
public class TraceCache {

    /** 最大缓存条数，防止极端情况 OOM */
    private static final int MAX_ENTRIES = 500;

    /** TTL 毫秒：30 分钟 */
    private static final long TTL_MS = 30 * 60 * 1000L;

    /** 缓存条目 */
    private record Entry(PipelineTrace trace, long storedAt) {}

    /** accessOrder=false → 插入顺序，淘汰最老的 */
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(128, 0.75f, false);

    /** 写入 trace（问答完成时调用） */
    public synchronized void put(String logId, PipelineTrace trace) {
        if (logId == null || trace == null) return;
        evictExpired();
        if (cache.size() >= MAX_ENTRIES) {
            // 淘汰最老的一条
            String eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }
        cache.put(logId, new Entry(trace, System.currentTimeMillis()));
    }

    /**
     * 取出并移除 trace（点踩时调用）.
     *
     * @return trace 的阶段列表，已转换为可序列化的 Map 结构；null 表示缓存已过期或不存在
     */
    public synchronized List<Map<String, Object>> takeStages(String logId) {
        Entry entry = cache.remove(logId);
        if (entry == null) return null;
        if (isExpired(entry)) return null;
        return serializeStages(entry.trace());
    }

    /** 一次性取出 intent + stages，取出后缓存自动移除 */
    public synchronized TraceData take(String logId) {
        Entry entry = cache.remove(logId);
        if (entry == null) return null;
        if (isExpired(entry)) return null;
        PipelineTrace t = entry.trace();
        return new TraceData(t.intent(), serializeStages(t));
    }

    private boolean isExpired(Entry entry) {
        return System.currentTimeMillis() - entry.storedAt() > TTL_MS;
    }

    private void evictExpired() {
        cache.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    /** 把 PipelineTrace.TraceStage record 转成可序列化的 Map */
    private static List<Map<String, Object>> serializeStages(PipelineTrace trace) {
        return trace.stages().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("label", s.label());
            m.put("startedAt", s.startedAt());
            m.put("finishedAt", s.finishedAt());
            m.put("durationMs", s.durationMs());
            m.put("status", s.status());
            m.put("statusLabel", s.statusLabel());
            m.put("detail", s.detail());
            m.put("data", s.data());
            return m;
        }).toList();
    }

    /** 取出的 trace 数据包 */
    public record TraceData(String intent, List<Map<String, Object>> stages) {}
}

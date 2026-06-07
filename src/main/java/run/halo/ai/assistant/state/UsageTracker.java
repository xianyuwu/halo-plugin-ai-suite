package run.halo.ai.assistant.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.assistant.endpoint.UsageLimit.ModelUsageRecord;
import run.halo.ai.assistant.endpoint.UsageLimit.ModelUsageSnapshot;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 模型用量跟踪器 — 内存态 + 跨日刷盘到 ConfigMap + 启动恢复.
 *
 * <p>核心数据结构:
 * <pre>
 *   daily: date "yyyy-MM-dd" → model name → ModelUsage
 * </pre>
 *
 * <p>写入路径: {@link #recordUsage} 自增 AtomicLong(线程安全,无锁).
 * <p>跨日检测: 每次写入时检查 {@code LocalDate.now()} 是否与缓存的 currentDate 变化,
 * 变化则把当日数据异步刷盘到 ConfigMap {@code ai-assistant-usage-{date}},然后清空.
 * <p>启动恢复: 构造器后台线程加载 today + yesterday 两个 ConfigMap(防跨日丢失).
 * <p>线程模型: 内存态读写无锁(ConcurrentHashMap + AtomicLong),ConfigMap IO 走 boundedElastic.
 */
@Slf4j
@Component
public class UsageTracker {

    private final ReactiveExtensionClient client;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 启动恢复窗口 — 加载过去 N 天的 ConfigMap */
    private static final int HISTORY_DAYS = 30;

    /** date → model → ModelUsage */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ModelUsage>> daily =
        new ConcurrentHashMap<>();

    /** 懒加载负向缓存: 标记已确认不存在的日期, 避免反复 ConfigMap IO */
    private final java.util.Set<String> negativeCache =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile String currentDate = LocalDate.now(ZoneId.systemDefault()).toString();
    private final ReentrantLock daySwitchLock = new ReentrantLock();

    /**
     * 定时刷盘调度器 — 防止插件中途崩溃/重启导致当天数据丢失.
     * 跨日刷盘窗口内 (最长 24h) 的数据靠这个兜底, 把数据丢失窗口从"今天一天"压到 5 分钟.
     */
    private final ScheduledExecutorService flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "usage-tracker-flush");
        t.setDaemon(true);
        return t;
    });

    public UsageTracker(ReactiveExtensionClient client) {
        this.client = client;
        loadFromConfigMap();
        // 首次立即刷一次 (空数据则跳过), 之后每 5 分钟刷盘
        // 把"启动后 5 分钟内崩溃"的数据丢失窗口从 5 分钟压到 0
        flushScheduler.scheduleAtFixedRate(
            this::scheduledFlush,
            0, 5, TimeUnit.MINUTES);
        log.info("[UsageTracker] 已启动定时刷盘 (首次立即, 之后每 5 分钟)");
    }

    /** 定时刷盘入口 — 仅刷当天; 历史日期已被持久化无需重写 */
    private void scheduledFlush() {
        try {
            String date = currentDate;
            var perModel = daily.get(date);
            if (perModel == null || perModel.isEmpty()) {
                return;  // 当天没数据, 跳过
            }
            log.debug("[UsageTracker] 定时刷盘当天数据: {}", date);
            flushToConfigMapAsync(date);
        } catch (Exception e) {
            log.warn("[UsageTracker] 定时刷盘失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[UsageTracker] 关闭: 停止定时任务并同步刷盘当天数据");
        flushScheduler.shutdown();
        try {
            if (!flushScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                flushScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 同步刷盘当天数据 — 避免 graceful shutdown / 插件禁用 时当天数据丢失
        String date = currentDate;
        var perModel = daily.get(date);
        if (perModel != null && !perModel.isEmpty()) {
            String json = buildPayloadJson(date, perModel);
            if (json != null) {
                try {
                    persistConfigMap("ai-assistant-usage-" + date, json);
                    log.info("[UsageTracker] 关闭时同步刷盘当天数据: {}", date);
                } catch (Exception e) {
                    log.warn("[UsageTracker] 关闭刷盘失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 单模型当日累计用量.
     */
    public static final class ModelUsage {
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong embeddingTokens = new AtomicLong();

        void increment(long prompt, long completion, boolean isFailure, long embeddingTokens) {
            this.promptTokens.addAndGet(prompt);
            this.completionTokens.addAndGet(completion);
            this.calls.incrementAndGet();
            if (isFailure) this.failures.incrementAndGet();
            this.embeddingTokens.addAndGet(embeddingTokens);
        }

        ModelUsageSnapshot snapshot() {
            return new ModelUsageSnapshot(
                promptTokens.get(),
                completionTokens.get(),
                calls.get(),
                failures.get(),
                embeddingTokens.get()
            );
        }
    }

    /**
     * 记录一次 LLM 调用.
     *
     * @param model         模型名
     * @param type          类型: "chat" / "embed" / "rerank"
     * @param prompt        prompt token 数
     * @param completion    completion token 数
     * @param isFailure     是否失败
     * @param embeddingTokens embedding token 数(仅 embed 用,其他传 0)
     */
    public void recordUsage(String model, String type, long prompt, long completion,
                            boolean isFailure, long embeddingTokens) {
        try {
            ensureCurrentDate();
            String today = currentDate;
            daily.computeIfAbsent(today, k -> new ConcurrentHashMap<>())
                 .computeIfAbsent(model, k -> new ModelUsage())
                 .increment(prompt, completion, isFailure, embeddingTokens);
        } catch (Exception e) {
            // 统计失败不阻塞主流程
            log.debug("[UsageTracker] recordUsage 异常: {}", e.getMessage());
        }
    }

    /** 跨日检测 — 用 tryLock 避免主流程阻塞 */
    private void ensureCurrentDate() {
        String today = LocalDate.now(ZoneId.systemDefault()).toString();
        if (today.equals(currentDate)) return;
        if (!daySwitchLock.tryLock()) return;
        try {
            String oldDate = currentDate;
            log.info("[UsageTracker] 检测到跨日: {} -> {}, 异步刷盘 {}", oldDate, today, oldDate);
            flushToConfigMapAsync(oldDate);
            // 保留 oldDate 在内存里到下次刷新(刷盘失败也有备份),但不再 increment
            currentDate = today;
        } finally {
            daySwitchLock.unlock();
        }
    }

    /** 读取当日所有模型的实时快照(便宜,纯内存) */
    public List<ModelUsageRecord> getTodayUsage() {
        String today = currentDate;
        var perModel = daily.get(today);
        if (perModel == null) return List.of();
        return perModel.entrySet().stream()
            .map(e -> new ModelUsageRecord(today, e.getKey(), e.getValue().snapshot()))
            .toList();
    }

    /**
     * 读取指定日期的用量 — 内存优先, 内存 miss 时懒加载 ConfigMap.
     *
     * <p>三种情况:
     * <ol>
     *   <li>内存已有 → 直接返回</li>
     *   <li>内存无 + 已负缓存 → 返回空 (跳过 IO)</li>
     *   <li>内存无 + 未负缓存 → 尝试 ConfigMap, 命中进 daily, 不存在进负缓存</li>
     * </ol>
     */
    public List<ModelUsageRecord> getUsageForDate(String date) {
        var perModel = daily.get(date);
        if (perModel != null) {
            return perModel.entrySet().stream()
                .map(e -> new ModelUsageRecord(date, e.getKey(), e.getValue().snapshot()))
                .toList();
        }
        if (negativeCache.contains(date)) {
            return List.of();
        }
        // 懒加载: 同步拉一次 (调用方在 boundedElastic 线程)
        loadConfigMapForDate(date);
        perModel = daily.get(date);
        if (perModel == null) {
            negativeCache.add(date);
            return List.of();
        }
        return perModel.entrySet().stream()
            .map(e -> new ModelUsageRecord(date, e.getKey(), e.getValue().snapshot()))
            .toList();
    }

    /** 当日某个模型已用 token(prompt + completion + embedding) */
    public long getTodayTokens(String model) {
        return getTodayUsage().stream()
            .filter(r -> r.model().equals(model))
            .mapToLong(r -> r.usage().totalTokens())
            .sum();
    }

    /** 当日所有模型已用 token */
    public long getTodayGlobalTokens() {
        return getTodayUsage().stream()
            .mapToLong(r -> r.usage().totalTokens())
            .sum();
    }

    /** 异步刷盘到 ConfigMap(后台线程) */
    private void flushToConfigMapAsync(String date) {
        var perModel = daily.get(date);
        if (perModel == null || perModel.isEmpty()) return;
        String json = buildPayloadJson(date, perModel);
        if (json == null) return;
        String cmName = "ai-assistant-usage-" + date;
        Mono.fromRunnable(() -> persistConfigMap(cmName, json))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                null,
                e -> log.warn("[UsageTracker] 刷盘 {} 失败: {}", cmName, e.getMessage())
            );
    }

    /** 序列化为 ConfigMap payload JSON. 返回 null 表示序列化失败或无数据 */
    private String buildPayloadJson(String date,
                                    Map<String, ModelUsage> perModel) {
        Map<String, Object> modelsJson = new LinkedHashMap<>();
        perModel.forEach((model, usage) -> {
            var s = usage.snapshot();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prompt", s.promptTokens());
            m.put("completion", s.completionTokens());
            m.put("calls", s.calls());
            m.put("failures", s.failures());
            if (s.embeddingTokens() > 0) m.put("embeddingTokens", s.embeddingTokens());
            modelsJson.put(model, m);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", date);
        data.put("models", modelsJson);
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("[UsageTracker] 序列化 {} 失败: {}", date, e.getMessage());
            return null;
        }
    }

    private void persistConfigMap(String name, String dataJson) {
        try {
            var existing = client.fetch(ConfigMap.class, name).block();
            if (existing != null) {
                existing.setData(Map.of("payload", dataJson));
                client.update(existing).block();
            } else {
                // CREATE 分支: 必须先 new Metadata().setName() 再 setMetadata, 否则 getMetadata() 为 null
                ConfigMap cm = new ConfigMap();
                Metadata md = new Metadata();
                md.setName(name);
                cm.setMetadata(md);
                cm.setData(Map.of("payload", dataJson));
                client.create(cm).block();
            }
        } catch (Exception e) {
            log.warn("[UsageTracker] 写 ConfigMap {} 失败: {}", name, e.getMessage());
        }
    }

    /**
     * 启动恢复窗口 — 加载过去 30 天的 ConfigMap 进内存.
     *
     * <p>为啥 30 天: 覆盖 ConsoleUsageEndpoint 的最常见 range (7d/30d) + 给最近
     * 跨日还未触发的情况留缓冲. 30 天 × N 模型 × 几 long 字段 = 几十 KB 内存,
     * 完全可接受.
     *
     * <p>不在启动窗口里的日期 (例如插件上线 >30 天后再查 30d 范围):
     * {@link #getUsageForDate} 会懒加载兜底.
     */
    public void loadFromConfigMap() {
        Mono.fromRunnable(() -> {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            for (int i = 0; i <= HISTORY_DAYS; i++) {
                loadConfigMapForDate(today.minusDays(i).toString());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            null,
            e -> log.warn("[UsageTracker] 启动加载失败: {}", e.getMessage())
        );
    }

    private void loadConfigMapForDate(String date) {
        String cmName = "ai-assistant-usage-" + date;
        try {
            var cmOpt = client.fetch(ConfigMap.class, cmName).block();
            if (cmOpt == null) {
                // 不存在 → 负缓存, 避免下次再 IO
                negativeCache.add(date);
                return;
            }
            // 找到了 → 清掉负缓存 (今天可能跨日后补 ConfigMap)
            negativeCache.remove(date);
            var data = cmOpt.getData();
            if (data == null) return;
            String payload = data.get("payload");
            if (payload == null || payload.isBlank()) return;
            JsonNode node = objectMapper.readTree(payload);
            JsonNode models = node.path("models");
            if (!models.isObject()) return;
            var perModel = daily.computeIfAbsent(date, k -> new ConcurrentHashMap<>());
            models.fields().forEachRemaining(e -> {
                String model = e.getKey();
                JsonNode m = e.getValue();
                var usage = new ModelUsage();
                usage.promptTokens.set(m.path("prompt").asLong(0));
                usage.completionTokens.set(m.path("completion").asLong(0));
                usage.calls.set(m.path("calls").asLong(0));
                usage.failures.set(m.path("failures").asLong(0));
                usage.embeddingTokens.set(m.path("embeddingTokens").asLong(0));
                perModel.put(model, usage);
            });
            log.info("[UsageTracker] 加载 {} 用量: {} 个模型", date, perModel.size());
        } catch (Exception e) {
            log.warn("[UsageTracker] 加载 {} 失败: {}", cmName, e.getMessage());
        }
    }
}

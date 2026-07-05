package run.halo.ai.suite.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.endpoint.UsageLimit.ModelUsageRecord;
import run.halo.ai.suite.endpoint.UsageLimit.ModelUsageSnapshot;
import run.halo.ai.suite.llm.UsageScenario;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 变化则把当日数据异步刷盘到 ConfigMap {@code ai-suite-usage-{date}},然后清空.
 * <p>启动恢复: 构造器后台线程加载 today + yesterday 两个 ConfigMap(防跨日丢失).
 * <p>线程模型: 内存态读写无锁(ConcurrentHashMap + AtomicLong),ConfigMap IO 走 boundedElastic.
 */
@Slf4j
@Component
public class UsageTracker {

    private final ReactiveExtensionClient client;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String USAGE_CONFIG_PREFIX = "ai-suite-usage-";
    private static final String LEGACY_USAGE_CONFIG_PREFIX = "ai-assistant-usage-";

    /** 启动恢复窗口 — 加载过去 N 天的 ConfigMap */
    private static final int HISTORY_DAYS = 30;
    private static final int CALL_LOG_RETENTION_DAYS = 30;

    /** date → model → ModelUsage */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ModelUsage>> daily =
        new ConcurrentHashMap<>();

    /** date → call logs */
    private final ConcurrentHashMap<String, List<ModelCallLog>> dailyCallLogs =
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
                    persistConfigMap(USAGE_CONFIG_PREFIX + date, json);
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
        /**
         * 已预占但尚未结算的 token — 用于并发下的 token 限流预扣.
         * <p>流程: 限流通过时 {@code reserve} 预占, 请求返回(成功/失败)时
         * {@code settle} 扣减预占并把实际用量记入 prompt/completion.
         * <p>语义: reserved 表示"在路上但还没回结果"的请求预估占用, 避免并发
         * 请求同时通过限额检查导致超限.
         */
        final AtomicLong reservedTokens = new AtomicLong();

        void increment(long prompt, long completion, boolean isFailure, long embeddingTokens) {
            this.promptTokens.addAndGet(prompt);
            this.completionTokens.addAndGet(completion);
            this.calls.incrementAndGet();
            if (isFailure) this.failures.incrementAndGet();
            this.embeddingTokens.addAndGet(embeddingTokens);
        }

        void add(ModelUsage other) {
            if (other == null) return;
            this.promptTokens.addAndGet(other.promptTokens.get());
            this.completionTokens.addAndGet(other.completionTokens.get());
            this.calls.addAndGet(other.calls.get());
            this.failures.addAndGet(other.failures.get());
            this.embeddingTokens.addAndGet(other.embeddingTokens.get());
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
        recordUsage(model, type, UsageScenario.UNKNOWN, prompt, completion, isFailure, embeddingTokens);
    }

    public void recordUsage(String model, String type, String scenario, long prompt, long completion,
                            boolean isFailure, long embeddingTokens) {
        recordUsage(model, type, scenario, prompt, completion, isFailure, embeddingTokens, "");
    }

    /**
     * 记录一次调用（带失败原因），失败时把异常摘要写入 ModelCallLog.error，供明细页展示。
     *
     * @param error 失败原因摘要（成功时传空串）
     */
    public void recordUsage(String model, String type, String scenario, long prompt, long completion,
                            boolean isFailure, long embeddingTokens, String error) {
        try {
            ensureCurrentDate();
            String today = currentDate;
            daily.computeIfAbsent(today, k -> new ConcurrentHashMap<>())
                 .computeIfAbsent(model, k -> new ModelUsage())
                 .increment(prompt, completion, isFailure, embeddingTokens);
            dailyCallLogs.computeIfAbsent(today, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(ModelCallLog.create(today, model, type, scenario, prompt, completion,
                    embeddingTokens, isFailure, 0, error == null ? "" : error));
        } catch (Exception e) {
            // 统计失败不阻塞主流程
            log.debug("[UsageTracker] recordUsage 异常: {}", e.getMessage());
        }
    }

    public ModelCallLogPage getCallLogs(LocalDate start, LocalDate end, String model,
                                        String type, String scenario, String status, int page, int size,
                                        String sort) {
        List<ModelCallLog> all = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            String date = cursor.toString();
            if (!dailyCallLogs.containsKey(date) && !negativeCache.contains(date)) {
                loadConfigMapForDate(date);
            }
            List<ModelCallLog> logs = dailyCallLogs.get(date);
            if (logs != null) {
                synchronized (logs) {
                    all.addAll(logs);
                }
            }
            cursor = cursor.plusDays(1);
        }

        String modelFilter = model == null ? "" : model.trim();
        String typeFilter = type == null ? "" : type.trim();
        String scenarioFilter = scenario == null ? "" : scenario.trim();
        String statusFilter = status == null ? "" : status.trim();
        boolean asc = "asc".equalsIgnoreCase(sort);
        List<ModelCallLog> filtered = all.stream()
            .filter(log -> modelFilter.isEmpty() || log.model().equals(modelFilter))
            .filter(log -> typeFilter.isEmpty() || "all".equals(typeFilter) || log.type().equals(typeFilter))
            .filter(log -> scenarioFilter.isEmpty() || "all".equals(scenarioFilter)
                || normalizeScenario(log.scenario()).equals(scenarioFilter))
            .filter(log -> {
                if (statusFilter.isEmpty() || "all".equals(statusFilter)) return true;
                if ("success".equals(statusFilter)) return !log.failure();
                if ("failed".equals(statusFilter)) return log.failure();
                return true;
            })
            .sorted((a, b) -> asc ? a.time().compareTo(b.time()) : b.time().compareTo(a.time()))
            .toList();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int total = filtered.size();
        int from = Math.min(Math.max(0, (safePage - 1) * safeSize), total);
        int to = Math.min(from + safeSize, total);
        return new ModelCallLogPage(total, safePage, safeSize, filtered.subList(from, to));
    }

    public List<String> getCallLogScenarios(LocalDate start, LocalDate end, String model) {
        return collectCallLogs(start, end).stream()
            .filter(log -> model == null || model.isBlank() || log.model().equals(model.trim()))
            .map(log -> normalizeScenario(log.scenario()))
            .distinct()
            .sorted()
            .toList();
    }

    public List<String> getCallLogTypes(LocalDate start, LocalDate end, String model) {
        List<ModelCallLog> all = collectCallLogs(start, end);
        String modelFilter = model == null ? "" : model.trim();
        return all.stream()
            .filter(log -> modelFilter.isEmpty() || log.model().equals(modelFilter))
            .map(ModelCallLog::type)
            .filter(type -> type != null && !type.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    public List<ModelCallLog> getFailedCallLogs(LocalDate start, LocalDate end, String model) {
        String modelFilter = model == null ? "" : model.trim();
        return collectCallLogs(start, end).stream()
            .filter(ModelCallLog::failure)
            .filter(log -> modelFilter.isEmpty() || log.model().equals(modelFilter))
            .toList();
    }

    private List<ModelCallLog> collectCallLogs(LocalDate start, LocalDate end) {
        List<ModelCallLog> all = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            String date = cursor.toString();
            if (!dailyCallLogs.containsKey(date) && !negativeCache.contains(date)) {
                loadConfigMapForDate(date);
            }
            List<ModelCallLog> logs = dailyCallLogs.get(date);
            if (logs != null) {
                synchronized (logs) {
                    all.addAll(logs);
                }
            }
            cursor = cursor.plusDays(1);
        }
        return all;
    }

    private String normalizeScenario(String scenario) {
        return scenario == null || scenario.isBlank() ? UsageScenario.UNKNOWN : scenario;
    }

    /** 跨日检测 — 用 tryLock 避免主流程阻塞 */
    private void ensureCurrentDate() {
        String today = LocalDate.now(ZoneId.systemDefault()).toString();
        if (today.equals(currentDate)) return;
        if (!daySwitchLock.tryLock()) {
            // 别的线程正在切日: 持锁线程切日是纯内存操作(currentDate=today + 异步刷盘),
            // 通常 <1ms 完成. 这里短暂自旋等它更新 currentDate, 避免本线程用旧 date 写入
            // 导致跨日瞬间的调用被记到昨天(当日少计、限额被绕过).
            // 最多等 ~50ms, 超时则用此刻读到的 currentDate(可能仍是旧值, 但已尽力).
            for (int i = 0; i < 5 && !today.equals(currentDate); i++) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return;
        }
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

    /**
     * 当日某个模型"已用 + 已预占"token — 用于并发限流的预扣判断.
     * <p>{@link #getTodayTokens} 只反映已结算的实际用量, 并发请求在路上时,
     * 多个请求会同时看到同一个"未超限"快照而全部通过, 导致实际超限.
     * 本方法把预留也算进去, 让 {@code LimitGuard} 的检查能正确拦截并发.
     */
    public long getTodayTokensWithReservation(String model) {
        var perModel = daily.get(currentDate);
        if (perModel == null) return 0;
        ModelUsage usage = perModel.get(model);
        if (usage == null) return 0;
        return usage.snapshot().totalTokens() + usage.reservedTokens.get();
    }

    /**
     * 预占 token — 限流通过后由 {@code LimitGuard} 调用, 表示"这个请求在路上".
     * <p>预占只是占位, 请求返回后由 {@link #recordUsage} 自动结算扣减.
     * 若返回值 >0 表示已预占, 调用方无需记 handle; 结算时机由 recordUsage 保证
     * (成功/失败两条路径都会结算).
     *
     * @return 预占后的累计预留数(含本次), 供日志/调试
     */
    public long reserve(String model, long estimatedTokens) {
        if (estimatedTokens <= 0) return 0;
        ModelUsage usage = daily
            .computeIfAbsent(currentDate, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(model, k -> new ModelUsage());
        return usage.reservedTokens.addAndGet(estimatedTokens);
    }

    /**
     * 结算预占 — 由 {@code LimitGuard} 在请求结束(成功/失败)时调用, 扣减预占额度.
     * <p>用 CAS 循环保证不会扣成负数(成功/失败两条路径都可能结算, 兜底 clamp 到 0).
     * <p>注意: 若请求跨日, 预占记在旧 date, 结算时 currentDate 已变, 会出现
     * "预占在昨天、结算找不到" 的情况 — 此时预占会随旧 date 的 ModelUsage 一起
     * 刷盘归档, 不影响当日限流正确性(最多让昨天的快照残留一点 reserved, 可接受).
     */
    public void settle(String model, long estimatedTokens) {
        if (estimatedTokens <= 0) return;
        var perModel = daily.get(currentDate);
        if (perModel == null) return;
        ModelUsage usage = perModel.get(model);
        if (usage == null) return;
        // CAS 扣减, 防止扣成负数
        long prev, next;
        do {
            prev = usage.reservedTokens.get();
            next = Math.max(0, prev - estimatedTokens);
            if (next == prev) return; // 已经是 0, 无需扣
        } while (!usage.reservedTokens.compareAndSet(prev, next));
    }

    /** 当日所有模型已用 token */
    public long getTodayGlobalTokens() {
        return getTodayUsage().stream()
            .mapToLong(r -> r.usage().totalTokens())
            .sum();
    }

    @FunctionalInterface
    private interface DayCleanup {
        DayCleanupResult apply(String date);
    }

    private record DayCleanupResult(boolean changed, long calls, long logs) {}

    public record UsageCleanupResult(int changedDays, long affectedCalls, long affectedLogs) {}

    public UsageCleanupResult mergeModelUsage(LocalDate start, LocalDate end,
                                              String sourceModel, String targetModel) {
        if (sourceModel == null || sourceModel.isBlank()) {
            throw new IllegalArgumentException("sourceModel is required");
        }
        if (targetModel == null || targetModel.isBlank()) {
            throw new IllegalArgumentException("targetModel is required");
        }
        if (sourceModel.equals(targetModel)) {
            return new UsageCleanupResult(0, 0, 0);
        }
        return cleanupRange(start, end, date -> {
            var perModel = daily.computeIfAbsent(date, k -> new ConcurrentHashMap<>());
            ModelUsage source = perModel.remove(sourceModel);
            long calls = 0;
            if (source != null) {
                perModel.computeIfAbsent(targetModel, k -> new ModelUsage()).add(source);
                calls += source.calls.get();
            }
            int logsChanged = 0;
            List<ModelCallLog> logs = dailyCallLogs.get(date);
            if (logs != null) {
                synchronized (logs) {
                    for (int i = 0; i < logs.size(); i++) {
                        ModelCallLog log = logs.get(i);
                        if (sourceModel.equals(log.model())) {
                            logs.set(i, log.withModel(targetModel));
                            logsChanged++;
                        }
                    }
                }
            }
            return new DayCleanupResult(source != null || logsChanged > 0, calls, logsChanged);
        });
    }

    public UsageCleanupResult deleteModelUsage(LocalDate start, LocalDate end, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        return cleanupRange(start, end, date -> {
            var perModel = daily.computeIfAbsent(date, k -> new ConcurrentHashMap<>());
            ModelUsage removed = perModel.remove(model);
            long calls = removed != null ? removed.calls.get() : 0;
            int logsRemoved = 0;
            List<ModelCallLog> logs = dailyCallLogs.get(date);
            if (logs != null) {
                synchronized (logs) {
                    int before = logs.size();
                    logs.removeIf(log -> model.equals(log.model()));
                    logsRemoved = before - logs.size();
                }
            }
            return new DayCleanupResult(removed != null || logsRemoved > 0, calls, logsRemoved);
        });
    }

    private UsageCleanupResult cleanupRange(LocalDate start, LocalDate end, DayCleanup cleanup) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("invalid date range");
        }
        daySwitchLock.lock();
        try {
            int changedDays = 0;
            long affectedCalls = 0;
            long affectedLogs = 0;
            LocalDate cursor = start;
            while (!cursor.isAfter(end)) {
                String date = cursor.toString();
                if (!daily.containsKey(date) && !negativeCache.contains(date)) {
                    loadConfigMapForDate(date);
                }
                DayCleanupResult result = cleanup.apply(date);
                if (result.changed()) {
                    changedDays++;
                    affectedCalls += result.calls();
                    affectedLogs += result.logs();
                    persistDate(date);
                    negativeCache.remove(date);
                }
                cursor = cursor.plusDays(1);
            }
            return new UsageCleanupResult(changedDays, affectedCalls, affectedLogs);
        } finally {
            daySwitchLock.unlock();
        }
    }

    private void persistDate(String date) {
        var perModel = daily.computeIfAbsent(date, k -> new ConcurrentHashMap<>());
        String json = buildPayloadJson(date, perModel);
        if (json != null) {
            persistConfigMap(USAGE_CONFIG_PREFIX + date, json);
        }
    }

    public int getCallLogRetentionDays() {
        return CALL_LOG_RETENTION_DAYS;
    }

    /** 异步刷盘到 ConfigMap(后台线程) */
    private void flushToConfigMapAsync(String date) {
        var perModel = daily.get(date);
        if (perModel == null || perModel.isEmpty()) return;
        String json = buildPayloadJson(date, perModel);
        if (json == null) return;
        String cmName = USAGE_CONFIG_PREFIX + date;
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
        List<ModelCallLog> logs = dailyCallLogs.get(date);
        if (logs != null && !logs.isEmpty()) {
            synchronized (logs) {
                data.put("callLogs", logs.stream().map(ModelCallLog::toMap).toList());
            }
        }
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
        String cmName = USAGE_CONFIG_PREFIX + date;
        String legacyCmName = LEGACY_USAGE_CONFIG_PREFIX + date;
        try {
            var cmOpt = client.fetch(ConfigMap.class, cmName).block();
            if (cmOpt == null) {
                cmOpt = client.fetch(ConfigMap.class, legacyCmName).block();
            }
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
            JsonNode callLogs = node.path("callLogs");
            if (callLogs.isArray()) {
                List<ModelCallLog> logs = dailyCallLogs.computeIfAbsent(date,
                    k -> Collections.synchronizedList(new ArrayList<>()));
                logs.clear();
                for (JsonNode item : callLogs) {
                    logs.add(ModelCallLog.fromJson(date, item));
                }
            }
            log.info("[UsageTracker] 加载 {} 用量: {} 个模型", date, perModel.size());
        } catch (Exception e) {
            log.warn("[UsageTracker] 加载 {} 失败: {}", cmName, e.getMessage());
        }
    }

    public record ModelCallLog(
        String id,
        String date,
        String time,
        String model,
        String type,
        String scenario,
        long promptTokens,
        long completionTokens,
        long embeddingTokens,
        long totalTokens,
        boolean failure,
        long durationMs,
        String error
    ) {
        static ModelCallLog create(String date, String model, String type, String scenario, long prompt,
                                   long completion, long embeddingTokens, boolean failure,
                                   long durationMs, String error) {
            return new ModelCallLog(
                UUID.randomUUID().toString(),
                date,
                Instant.now().toString(),
                model == null ? "" : model,
                type == null ? "" : type,
                scenario == null || scenario.isBlank() ? UsageScenario.UNKNOWN : scenario,
                Math.max(0, prompt),
                Math.max(0, completion),
                Math.max(0, embeddingTokens),
                Math.max(0, prompt) + Math.max(0, completion) + Math.max(0, embeddingTokens),
                failure,
                Math.max(0, durationMs),
                error == null ? "" : error
            );
        }

        static ModelCallLog fromJson(String fallbackDate, JsonNode node) {
            long prompt = node.path("promptTokens").asLong(0);
            long completion = node.path("completionTokens").asLong(0);
            long embedding = node.path("embeddingTokens").asLong(0);
            return new ModelCallLog(
                node.path("id").asText(UUID.randomUUID().toString()),
                node.path("date").asText(fallbackDate),
                node.path("time").asText(""),
                node.path("model").asText(""),
                node.path("type").asText(""),
                node.path("scenario").asText(UsageScenario.UNKNOWN),
                prompt,
                completion,
                embedding,
                node.path("totalTokens").asLong(prompt + completion + embedding),
                node.path("failure").asBoolean(false),
                node.path("durationMs").asLong(0),
                node.path("error").asText("")
            );
        }

        ModelCallLog withModel(String newModel) {
            return new ModelCallLog(
                id,
                date,
                time,
                newModel == null ? "" : newModel,
                type,
                scenario,
                promptTokens,
                completionTokens,
                embeddingTokens,
                totalTokens,
                failure,
                durationMs,
                error
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("date", date);
            data.put("time", time);
            data.put("model", model);
            data.put("type", type);
            data.put("scenario", scenario);
            data.put("promptTokens", promptTokens);
            data.put("completionTokens", completionTokens);
            data.put("embeddingTokens", embeddingTokens);
            data.put("totalTokens", totalTokens);
            data.put("failure", failure);
            data.put("durationMs", durationMs);
            data.put("error", error);
            return data;
        }
    }

    public record ModelCallLogPage(int total, int page, int size, List<ModelCallLog> items) {}
}

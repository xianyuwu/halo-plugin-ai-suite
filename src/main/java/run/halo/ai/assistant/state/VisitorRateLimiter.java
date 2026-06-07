package run.halo.ai.assistant.state;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.ai.assistant.endpoint.UsageLimit.LimitDecision;
import run.halo.ai.assistant.endpoint.UsageLimit.VisitorRateLimitConfig;

/**
 * 访客限流 — 按客户端 IP 维度限制对话次数.
 *
 * <p>策略: 双重滑窗
 * <ul>
 *   <li>每日上限: 当日 0 时起算, 跨日自动重置</li>
 *   <li>每小时上限: 滑动 1 小时窗口, 用时间戳队列实现</li>
 * </ul>
 *
 * <p>存储: 内存 ConcurrentHashMap + 同步队列. 重启清零 (防滥用是"短时"防护,
 * 不是账单, 持久化收益不大). 后台线程每 10 分钟扫一次清过期条目, 避免内存泄漏.
 *
 * <p>白名单: 精确 IP 字符串匹配 (如 192.168.1.10 或 ::1). CIDR 暂不支持 —
 * 个人博客场景下白名单通常只有管理员自己 1-2 个 IP.
 */
@Slf4j
@Component
public class VisitorRateLimiter {

    /** IP → 当日计数器 (date 字符串作为 key 的一部分, 跨日自动失效) */
    private final ConcurrentHashMap<String, DailyCounter> dailyCounters = new ConcurrentHashMap<>();

    /** IP → 最近 1h 时间戳队列 (滑动窗口) */
    private final ConcurrentHashMap<String, Deque<Instant>> hourlyWindows = new ConcurrentHashMap<>();

    /** DailyCounter 内部锁, 避免 get-then-update race */
    private final Map<DailyCounter, ReentrantLock> dailyLocks = new ConcurrentHashMap<>();

    /**
     * 同步检查 — 给 LimitGuard 链式调用.
     *
     * @return 命中限制 → reject(reason, modelName=null); 未命中 / 关闭 / 白名单 → ok
     */
    /**
     * 同步检查 — cfg 由调用方从反应式链传入 (避免在 reactor 线程上 .block()).
     *
     * @return 命中限制 → reject(reason, modelName=null); 未命中 / 关闭 / 白名单 → ok
     */
    public LimitDecision check(String ip, VisitorRateLimitConfig cfg) {
        if (cfg == null || !cfg.enabled()) return LimitDecision.ok();
        if (ip == null || ip.isBlank()) return LimitDecision.ok();
        if (isWhitelisted(ip, cfg.whitelist())) return LimitDecision.ok();

        // 每日上限
        if (cfg.dailyLimit() > 0) {
            int used = incrementDaily(ip);
            if (used > cfg.dailyLimit()) {
                return LimitDecision.reject(
                    "本 IP 当日对话已达上限 " + cfg.dailyLimit() + " 次 (已用 " + used + ")",
                    null);
            }
        }
        // 每小时上限
        if (cfg.hourlyLimit() > 0) {
            int used = recordHourlyHit(ip);
            if (used > cfg.hourlyLimit()) {
                return LimitDecision.reject(
                    "本 IP 当前请求过于频繁 (1h 上限 " + cfg.hourlyLimit() + " 次, 已用 " + used + ")",
                    null);
            }
        }
        return LimitDecision.ok();
    }

    private int incrementDaily(String ip) {
        String today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString();
        String key = ip + "|" + today;
        DailyCounter counter = dailyCounters.computeIfAbsent(key, k -> new DailyCounter(today, 0));
        ReentrantLock lock = dailyLocks.computeIfAbsent(counter, k -> new ReentrantLock());
        lock.lock();
        try {
            // 跨日检查: counter.date 跟今天不一致 → 重置
            if (!today.equals(counter.date)) {
                counter.date = today;
                counter.count = 0;
            }
            counter.count++;
            return counter.count;
        } finally {
            lock.unlock();
        }
    }

    private int recordHourlyHit(String ip) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(3600);
        Deque<Instant> q = hourlyWindows.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            q.addLast(now);
            return q.size();
        }
    }

    /** IP 白名单匹配: 精确字符串 + 通配 `*` (匹配所有 IPv4/IPv6) */
    private static boolean isWhitelisted(String ip, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) return false;
        for (String rule : whitelist) {
            String trimmed = rule.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals(ip) || trimmed.equals("*")) return true;
        }
        return false;
    }

    /**
     * 后台定期清理 — 用单线程守护线程, 避免引入 @EnableScheduling.
     * 每 10 分钟扫一次, 删过期条目 (24h 前的 daily 计数 / 1h 前的 hourly 队列).
     * 防止 IP 池增长导致内存泄漏.
     */
    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void startCleanup() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "visitor-rate-limit-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleWithFixedDelay(this::cleanup, 10, 10, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stopCleanup() {
        if (cleanupExecutor != null) cleanupExecutor.shutdownNow();
    }

    void cleanup() {
        Instant hourlyCutoff = Instant.now().minusSeconds(3600);
        String today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString();

        int removedHourly = 0;
        for (var entry : hourlyWindows.entrySet()) {
            Deque<Instant> q = entry.getValue();
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst().isBefore(hourlyCutoff)) q.pollFirst();
                if (q.isEmpty()) {
                    hourlyWindows.remove(entry.getKey(), q);
                    removedHourly++;
                }
            }
        }

        int removedDaily = 0;
        for (var key : dailyCounters.keySet()) {
            if (!key.endsWith("|" + today)) {
                DailyCounter removed = dailyCounters.remove(key);
                if (removed != null) {
                    dailyLocks.remove(removed);
                    removedDaily++;
                }
            }
        }

        if (removedHourly > 0 || removedDaily > 0) {
            log.debug("[VisitorRateLimiter] 清理过期条目: hourly={}, daily={}",
                removedHourly, removedDaily);
        }
    }

    private static class DailyCounter {
        volatile String date;
        volatile int count;
        DailyCounter(String date, int count) { this.date = date; this.count = count; }
    }
}

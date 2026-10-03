package cn.rainwu.halo.ai.suite.endpoint;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** Bounded task history. Never evicts running tasks to make room for a new request. */
final class PetJobRegistry {
    private static final long TTL = Duration.ofMinutes(30).toMillis();
    private static final int MAX_HISTORY = 128;
    private static final int MAX_RUNNING = 16;
    private final Map<String, ConsolePetEndpoint.PetJob> jobs = new LinkedHashMap<>();
    private final LongSupplier clock;

    PetJobRegistry() { this(System::currentTimeMillis); }
    PetJobRegistry(LongSupplier clock) { this.clock = clock; }

    synchronized void add(ConsolePetEndpoint.PetJob job) {
        prune();
        long running = jobs.values().stream().filter(item -> item.finishedAt() == 0).count();
        if (running >= MAX_RUNNING) throw new IllegalStateException("正在处理的宠物任务较多，请等待已有任务完成");
        if (jobs.size() >= MAX_HISTORY) {
            jobs.entrySet().stream().filter(entry -> entry.getValue().finishedAt() != 0)
                .min(Comparator.comparingLong(entry -> entry.getValue().finishedAt()))
                .ifPresent(entry -> jobs.remove(entry.getKey()));
        }
        jobs.put((String) job.toMap().get("id"), job);
    }

    synchronized ConsolePetEndpoint.PetJob get(String id) {
        prune();
        return jobs.get(id);
    }

    synchronized void cancelAll() {
        jobs.values().forEach(ConsolePetEndpoint.PetJob::cancel);
        jobs.clear();
    }

    private void prune() {
        long cutoff = clock.getAsLong() - TTL;
        jobs.values().removeIf(job -> job.finishedAt() != 0 && job.finishedAt() <= cutoff);
    }
}

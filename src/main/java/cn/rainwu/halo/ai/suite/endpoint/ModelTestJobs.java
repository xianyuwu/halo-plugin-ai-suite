package cn.rainwu.halo.ai.suite.endpoint;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/** Temporary, owner-scoped model tests. Request IDs also serve as idempotency keys. */
@Component
final class ModelTestJobs {
    private static final long TTL = Duration.ofMinutes(30).toMillis();
    private static final int MAX_HISTORY = 128;
    private static final int MAX_RUNNING = 4;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Duration timeout;
    private boolean stopped;

    ModelTestJobs() { this(System::currentTimeMillis, Duration.ofMinutes(6)); }
    ModelTestJobs(LongSupplier clock, Duration timeout) {
        this.clock = clock;
        this.timeout = timeout;
    }

    record TestRequest(String requestId, String kind, String model, int dimensions) {
        TestRequest validate() {
            if (requestId == null || !requestId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少有效的测试请求编号");
            }
            if (kind == null || !Set.of("chat", "queryRewrite", "embedding", "rerank", "image").contains(kind)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的模型测试类型");
            }
            if (dimensions < 0 || (model != null && model.length() > 256)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型测试参数无效");
            }
            return new TestRequest(UUID.fromString(requestId).toString(), kind,
                model == null ? "" : model.trim(), dimensions);
        }
    }

    synchronized Job submit(String owner, TestRequest request, ContextView context,
                            Supplier<Mono<Map<String, Object>>> action) {
        if (stopped) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "插件正在停止");
        prune();
        String key = owner + ":" + request.requestId();
        Job existing = jobs.get(key);
        if (existing != null) {
            if (!existing.request.equals(request)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请求编号已用于其他测试参数");
            }
            return existing;
        }
        if (jobs.values().stream().filter(Job::running).count() >= MAX_RUNNING) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "正在执行的模型测试较多，请稍后再试");
        }
        if (jobs.size() >= MAX_HISTORY) {
            jobs.entrySet().stream().filter(e -> !e.getValue().running())
                .min(Comparator.comparingLong(e -> e.getValue().finishedAt()))
                .ifPresent(e -> jobs.remove(e.getKey()));
        }
        Job job = new Job(request, clock);
        jobs.put(key, job);
        // A separate subscription survives cancellation of the browser request; retain security context.
        job.setSubscription(Mono.defer(action)
            .switchIfEmpty(Mono.error(new IllegalStateException("模型未返回测试结果")))
            .timeout(timeout)
            .contextWrite(context)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(job::finish, job::fail));
        return job;
    }

    synchronized Job get(String owner, String id) {
        prune();
        return jobs.get(owner + ":" + id);
    }

    private void prune() {
        long cutoff = clock.getAsLong() - TTL;
        jobs.values().removeIf(job -> !job.running() && job.finishedAt() <= cutoff);
    }

    @PreDestroy
    synchronized void stop() {
        stopped = true;
        jobs.values().forEach(Job::cancel);
        jobs.clear();
    }

    static final class Job {
        private final TestRequest request;
        private final LongSupplier clock;
        private final long createdAt;
        private long finishedAt;
        private String status = "pending";
        private Map<String, Object> result;
        private String error;
        private Disposable subscription;

        Job(TestRequest request, LongSupplier clock) {
            this.request = request;
            this.clock = clock;
            createdAt = clock.getAsLong();
        }
        synchronized boolean running() { return "pending".equals(status); }
        synchronized long finishedAt() { return finishedAt; }
        synchronized void setSubscription(Disposable subscription) {
            this.subscription = subscription;
            if (!running()) subscription.dispose();
        }
        synchronized void finish(Map<String, Object> result) {
            if (!running()) return;
            this.result = Map.copyOf(result);
            status = "done";
            finishedAt = clock.getAsLong();
        }
        synchronized void fail(Throwable error) {
            if (!running()) return;
            this.error = error instanceof java.util.concurrent.TimeoutException
                ? "测试执行超时；供应商可能仍在处理，请勿立即重复测试"
                : ConsoleConfigEndpoint.extractErrorMessage(error);
            status = "failed";
            finishedAt = clock.getAsLong();
        }
        synchronized void cancel() {
            fail(new IllegalStateException("插件已停止，测试任务中断"));
            if (subscription != null) subscription.dispose();
        }
        synchronized Map<String, Object> snapshot() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", request.requestId());
            data.put("kind", request.kind());
            data.put("status", status);
            data.put("createdAt", createdAt);
            if (result != null) data.put("result", result);
            if (error != null) data.put("error", error);
            if (!running()) data.put("finishedAt", finishedAt);
            return data;
        }
    }
}

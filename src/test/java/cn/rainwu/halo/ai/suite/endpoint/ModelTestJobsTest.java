package cn.rainwu.halo.ai.suite.endpoint;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

class ModelTestJobsTest {
    private ModelTestJobs.TestRequest request() {
        return new ModelTestJobs.TestRequest(UUID.randomUUID().toString(), "image", "model", 0);
    }

    @Test void slowTaskReturnsImmediatelyAndDuplicateSubmissionRunsOnlyOnce() {
        var registry = new ModelTestJobs();
        try {
            var source = Sinks.<Map<String, Object>>one();
            var calls = new AtomicInteger();
            var request = request();
            var job = registry.submit("admin", request, Context.of("owner", "admin"),
                () -> Mono.deferContextual(ctx -> {
                    assertThat(ctx.<String>get("owner")).isEqualTo("admin");
                    calls.incrementAndGet();
                    return source.asMono();
                }));
            assertThat(job.snapshot()).containsEntry("status", "pending");
            assertThat(registry.submit("admin", request, Context.empty(), () -> {
                throw new AssertionError("must not submit twice");
            })).isSameAs(job);
            await().atMost(Duration.ofSeconds(2)).until(() -> calls.get() == 1);
            source.tryEmitValue(Map.of("imageCount", 1));
            await().atMost(Duration.ofSeconds(2)).until(() -> !job.running());
            assertThat(job.snapshot()).containsEntry("status", "done")
                .containsEntry("result", Map.of("imageCount", 1));
            assertThat(registry.submit("admin", request, Context.empty(), Mono::empty)).isSameAs(job);
            assertThat(registry.get("other", request.requestId())).isNull();
            assertThatThrownBy(() -> registry.submit("admin",
                new ModelTestJobs.TestRequest(request.requestId(), "image", "changed", 0),
                Context.empty(), Mono::empty)).hasMessageContaining("409");
        } finally { registry.stop(); }
    }

    @Test void runningTasksAreBoundedAndShutdownCancelsThem() {
        var registry = new ModelTestJobs();
        var cancelled = new AtomicInteger();
        var subscribed = new AtomicInteger();
        for (int i = 0; i < 4; i++) registry.submit("admin", request(), Context.empty(),
            () -> Mono.<Map<String, Object>>never().doOnSubscribe(s -> subscribed.incrementAndGet())
                .doOnCancel(cancelled::incrementAndGet));
        // Wait until every subscription is active before checking cancellation.
        await().atMost(Duration.ofSeconds(2)).until(() -> subscribed.get() == 4);
        assertThatThrownBy(() -> registry.submit("admin", request(), Context.empty(), Mono::empty))
            .hasMessageContaining("429");
        registry.stop();
        assertThat(cancelled.get()).isEqualTo(4);
        assertThatThrownBy(() -> registry.submit("admin", request(), Context.empty(), Mono::empty))
            .hasMessageContaining("503");
    }

    @Test void executionTimeoutAndEmptyResultReachTerminalFailure() {
        var registry = new ModelTestJobs(System::currentTimeMillis, Duration.ofMillis(40));
        try {
            var timeout = registry.submit("admin", request(), Context.empty(), Mono::never);
            var empty = registry.submit("admin", request(), Context.empty(), Mono::empty);
            await().atMost(Duration.ofSeconds(2)).until(() -> !timeout.running() && !empty.running());
            assertThat(timeout.snapshot()).containsEntry("status", "failed");
            assertThat((String) timeout.snapshot().get("error")).contains("超时");
            assertThat(empty.snapshot()).containsEntry("error", "模型未返回测试结果");
            timeout.finish(Map.of("imageCount", 1));
            assertThat(timeout.snapshot()).containsEntry("status", "failed");
        } finally { registry.stop(); }
    }

    @Test void onlyCompletedHistoryExpiresAndHistoryIsBounded() {
        var clock = new AtomicLong(1000);
        var registry = new ModelTestJobs(clock::get, Duration.ofMinutes(6));
        try {
            var request = request();
            var done = registry.submit("admin", request, Context.empty(), () -> Mono.just(Map.of("imageCount", 1)));
            var pendingRequest = request();
            registry.submit("admin", pendingRequest, Context.empty(), Mono::never);
            await().atMost(Duration.ofSeconds(2)).until(() -> !done.running());
            clock.addAndGet(Duration.ofMinutes(31).toMillis());
            assertThat(registry.get("admin", request.requestId())).isNull();
            assertThat(registry.get("admin", pendingRequest.requestId())).isNotNull();
            String first = null;
            for (int i = 0; i < 130; i++) {
                var next = request();
                if (i == 0) first = next.requestId();
                var job = registry.submit("admin", next, Context.empty(), () -> Mono.just(Map.of("imageCount", 1)));
                await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(1)).until(() -> !job.running());
                clock.incrementAndGet();
            }
            assertThat(registry.get("admin", first)).isNull();
            assertThat(registry.get("admin", pendingRequest.requestId())).isNotNull();
        } finally { registry.stop(); }
    }
}

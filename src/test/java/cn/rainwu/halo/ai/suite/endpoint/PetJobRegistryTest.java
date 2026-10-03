package cn.rainwu.halo.ai.suite.endpoint;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PetJobRegistryTest {
    @Test void expiredTerminalJobsAreRemovedButRunningJobsArePreserved() {
        var time = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        var registry = new PetJobRegistry(time::get);
        var done = new ConsolePetEndpoint.PetJob("done"); done.finish("pet");
        registry.add(done); registry.add(new ConsolePetEndpoint.PetJob("running"));
        time.set(System.currentTimeMillis() + java.time.Duration.ofMinutes(31).toMillis());
        assertThat(registry.get("done")).isNull(); assertThat(registry.get("running")).isNotNull();
    }

    @Test void limitsRunningJobsInsteadOfSilentlyEvictingThem() {
        var registry = new PetJobRegistry();
        for (int i=0;i<16;i++) registry.add(new ConsolePetEndpoint.PetJob("job-" + i));
        assertThatThrownBy(() -> registry.add(new ConsolePetEndpoint.PetJob("overflow"))).hasMessageContaining("任务较多");
        assertThat(registry.get("job-0")).isNotNull();
    }

    @Test void shutdownCancelsActiveSubscriptionsAndClearsHistory() {
        var registry = new PetJobRegistry();
        var job = new ConsolePetEndpoint.PetJob("active");
        var disposed = new java.util.concurrent.atomic.AtomicBoolean();
        job.setSubscription(() -> disposed.set(true)); registry.add(job);
        registry.cancelAll(); assertThat(disposed.get()).isTrue(); assertThat(registry.get("active")).isNull();
        assertThat(job.toMap()).containsEntry("status", "failed");
    }

    @Test void historyIsBoundedAndTerminalStateCannotBeOverwritten() {
        var registry = new PetJobRegistry();
        for (int i=0;i<140;i++) { var job = new ConsolePetEndpoint.PetJob("job-" + i); job.finish("pet"); registry.add(job); }
        assertThat(registry.get("job-0")).isNull();
        var job = registry.get("job-139"); job.fail("late failure");
        assertThat(job.toMap()).containsEntry("status", "done");
    }
}

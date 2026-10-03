package cn.rainwu.halo.ai.suite.service;

import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/** Request-scoped observations only; never schedules or retries generation. */
public record PetGenerationProgress(String stage, String state) {
    public static final String CONTEXT_KEY = PetGenerationProgress.class.getName();

    public static <T> Mono<T> track(Mono<T> source, String stage, String state) {
        return Mono.deferContextual(context -> {
            Consumer<PetGenerationProgress> observer = context.getOrDefault(CONTEXT_KEY, event -> {});
            observer.accept(new PetGenerationProgress(stage, state));
            return source;
        });
    }

    public static <T> Mono<T> completed(T result, String state) {
        return track(Mono.just(result), "frame-complete", state);
    }
}

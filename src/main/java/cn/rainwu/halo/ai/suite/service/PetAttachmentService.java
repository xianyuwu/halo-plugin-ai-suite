package cn.rainwu.halo.ai.suite.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/** Durable ownership on attachment metadata, with conservative retryable garbage collection. */
@Service
@Slf4j
@RequiredArgsConstructor
public class PetAttachmentService {
    static final String OWNER = "ai-suite.halo.run/asset-owner";
    static final String CREATED = "ai-suite.halo.run/asset-created-at";
    static final String PET_ASSET = "generated-pet";
    // Longer than the maximum generation task; never collect an in-flight upload.
    static final Duration GRACE = Duration.ofHours(1);
    private final ReactiveExtensionClient client;
    private final AttachmentService attachments;
    private final PetStore store;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private Disposable timer;

    @PostConstruct
    void start() {
        timer = Flux.interval(Duration.ofMinutes(10))
            .concatMap(ignored -> collectUnused().onErrorResume(error -> {
                log.warn("宠物附件回收未完成，将稍后重试");
                return Mono.empty();
            })).subscribe();
    }

    @PreDestroy
    void stop() { if (timer != null) timer.dispose(); }

    Mono<String> upload(String policy, byte[] bytes, String filename) {
        FilePart file = new FilePart() {
            public String name() { return "file"; }
            public String filename() { return filename; }
            public HttpHeaders headers() {
                var headers = new HttpHeaders(); headers.setContentType(MediaType.IMAGE_PNG); return headers;
            }
            public Flux<org.springframework.core.io.buffer.DataBuffer> content() {
                return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)));
            }
            public Mono<Void> transferTo(java.nio.file.Path path) {
                return Mono.<Void>fromRunnable(() -> {
                    try { java.nio.file.Files.write(path, bytes); }
                    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
            }
        };
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
            .filter(auth -> auth != null && auth.isAuthenticated())
            .switchIfEmpty(Mono.error(new IllegalStateException("上传宠物图片需要登录")))
            .flatMap(auth -> attachments.upload(auth.getName(), policy, null, file, attachment -> {
                var annotations = attachment.getMetadata().getAnnotations() == null
                    ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(attachment.getMetadata().getAnnotations());
                annotations.put(OWNER, PET_ASSET);
                annotations.put(CREATED, Instant.now().toString());
                attachment.getMetadata().setAnnotations(annotations);
            }))
            .flatMap(attachment -> attachments.getPermalink(attachment)
                .switchIfEmpty(Mono.error(new IllegalStateException("附件未返回图片地址"))))
            .map(java.net.URI::toString).timeout(Duration.ofSeconds(60));
    }

    public Mono<Integer> collectUnused() {
        return Mono.using(() -> sweeping.compareAndSet(false, true), acquired -> {
            if (!acquired) return Mono.just(0);
            // If configuration is corrupt, stop before listing/deleting any attachments.
            return store.list().flatMap(pets -> client.listAll(Attachment.class, new ListOptions(), Sort.unsorted())
                .filter(attachment -> collectible(attachment, Instant.now()))
                .concatMap(attachment -> attachments.getPermalink(attachment)
                    .flatMap(url -> store.list().flatMap(latest -> {
                        if (references(latest).contains(url.toString())) return Mono.just(0);
                        // Halo's attachment reconciler performs physical storage deletion and retry.
                        return client.fetch(Attachment.class, attachment.getMetadata().getName())
                            .filter(fresh -> collectible(fresh, Instant.now()))
                            .flatMap(fresh -> client.delete(fresh).thenReturn(1)).defaultIfEmpty(0);
                    }))
                    .onErrorResume(error -> {
                        log.warn("宠物附件 {} 暂未回收，将稍后重试", attachment.getMetadata().getName());
                        return Mono.just(0);
                    }))
                .reduce(0, Integer::sum));
        }, acquired -> { if (acquired) sweeping.set(false); }, true);
    }

    static boolean collectible(Attachment attachment, Instant now) {
        var metadata = attachment.getMetadata();
        if (metadata == null || metadata.getDeletionTimestamp() != null || metadata.getAnnotations() == null) return false;
        var annotations = metadata.getAnnotations();
        if (!PET_ASSET.equals(annotations.get(OWNER))) return false;
        try { return !Instant.parse(annotations.get(CREATED)).plus(GRACE).isAfter(now); }
        catch (RuntimeException error) { return false; }
    }

    static Set<String> references(List<PetStore.PetRecord> pets) {
        Set<String> urls = new HashSet<>();
        for (var pet : pets) {
            if (pet.getImages() != null) urls.addAll(pet.getImages().values());
            if (pet.getPublishedVersion() != null && pet.getPublishedVersion().getImages() != null) {
                urls.addAll(pet.getPublishedVersion().getImages().values());
            }
            urls.add(pet.getOriginalMasterUrl()); urls.add(pet.getCleanedMasterUrl());
            if (pet.getExpressionCandidates() != null) pet.getExpressionCandidates().values().forEach(candidate -> {
                urls.add(candidate.getImageUrl()); urls.add(candidate.getOriginalUrl()); urls.add(candidate.getMasterUrl());
            });
        }
        urls.remove(null);
        return urls;
    }
}

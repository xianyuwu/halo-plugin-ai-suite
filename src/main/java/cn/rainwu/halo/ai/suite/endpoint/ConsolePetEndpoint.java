package cn.rainwu.halo.ai.suite.endpoint;

import cn.rainwu.halo.ai.suite.service.PetGeneratorService;
import cn.rainwu.halo.ai.suite.service.PetGenerationProgress;
import cn.rainwu.halo.ai.suite.service.PetStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * AI 贴纸宠物管理端点：上传照片异步生成宠物、列表、删除、表情重生成。
 *
 * <p>生成走 job 模式（同脑图批量生成）：POST 返回 jobId，前端轮询 GET /pets/jobs/{jobId}。
 * 图像生成单次要 10-60s，同步接口会扛不住网关超时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsolePetEndpoint implements CustomEndpoint {

    private static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_STYLES = Set.of("soft-3d", "chibi", "flat", "pixel");

    private final PetGeneratorService petGeneratorService;
    private final PetStore petStore;
    private final run.halo.app.extension.ReactiveExtensionClient client;
    private final PetJobRegistry jobs = new PetJobRegistry();

    @jakarta.annotation.PreDestroy
    void stopJobs() { jobs.cancelAll(); }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/pets/generate", this::handleGenerate)
            .POST("/pets/prompt-preview", this::handlePromptPreview)
            .POST("/pets/{id}/expressions/{state}/edit", this::handleExpressionEdit)
            .POST("/pets/{id}/expressions/{state}/cancel-edit", request -> handleCandidateReview(request, "cancel-edit"))
            .POST("/pets/{id}/background/edge-preview", request -> handleEdges(request, false))
            .POST("/pets/{id}/background/edge-apply", request -> handleEdges(request, true))
            .POST("/pets/{id}/expressions/generate", this::handleGenerateCandidates)
            .POST("/pets/{id}/expressions/{state}/approve", request -> handleCandidateReview(request, "approve"))
            .POST("/pets/{id}/expressions/{state}/cleanup", request -> handleCandidateReview(request, "cleanup"))
            .POST("/pets/{id}/expressions/{state}/reset", request -> handleCandidateReview(request, "reset"))
            .GET("/pets/jobs/{jobId}", this::handleGetJob)
            .GET("/pets/list", this::handleList)
            .GET("/pets/model-status", request -> petGeneratorService.imageModelStatus()
                .flatMap(status -> ServerResponse.ok().bodyValue(status)))
            .GET("/pets/policies", this::handlePolicies)
            .GET("/pets/presets", this::handlePresets)
            .DELETE("/pets/{id}", this::handleDelete)
            .POST("/pets/{id}/rename", this::handleRename)
            .POST("/pets/{id}/background/cleanup", this::handleCleanupBackground)
            .POST("/pets/{id}/background/approve", this::handleApproveBackground)
            .POST("/pets/{id}/background/reset", this::handleResetBackground)
            // 兼容已经打开旧版 Console 的请求；新前端使用 /background/cleanup。
            .POST("/pets/{id}/cleanup-background", this::handleCleanupBackground)
            .POST("/pets/{id}/regenerate-master", this::handleRegenerateMaster)
            .POST("/pets/{id}/regenerate", this::handleRegenerate)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> handleRename(ServerRequest request) {
        return request.bodyToMono(RenameRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("请填写宠物名称")))
            .flatMap(body -> petGeneratorService.renamePet(request.pathVariable("id"), body.getName()))
            .flatMap(record -> ServerResponse.ok().bodyValue(Map.of("success", true, "pet", record)))
            .onErrorResume(error -> badRequest("改名失败：" + error.getMessage()));
    }

    @lombok.Data
    private static class RenameRequest {
        private String name;
    }

    private Mono<ServerResponse> handleGenerate(ServerRequest request) {
        return request.multipartData()
            .flatMap(parts -> {
                Part filePart = parts.getFirst("file");
                if (!(filePart instanceof FilePart file)) {
                    return badRequest("缺少照片文件（字段名 file）");
                }
                String mediaType = file.headers().getContentType() != null
                    ? file.headers().getContentType().toString() : "";
                if (!ALLOWED_TYPES.contains(mediaType)) {
                    return badRequest("仅支持 JPEG / PNG / WebP 照片");
                }
                String name = formValue(parts, "name");
                String style = formValue(parts, "style");
                if (!ALLOWED_STYLES.contains(style)) {
                    style = "chibi";
                }
                boolean withExpressions = !"false".equals(formValue(parts, "withExpressions"));
                String customPrompt = formValue(parts, "customPrompt");
                String finalStyle = style;
                return DataBufferUtils.join(file.content(), (int) MAX_PHOTO_BYTES)
                    .flatMap(buffer -> {
                        if (buffer.readableByteCount() > MAX_PHOTO_BYTES) {
                            DataBufferUtils.release(buffer);
                            return badRequest("照片不能超过 5MB");
                        }
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        PetJob job = new PetJob(UUID.randomUUID().toString());
                        jobs.add(job);
                        String finalName = name;
                        // 生成任务异步跑在 boundedElastic，Reactor 上下文（含 Spring
                        // Security 的认证信息）不会自动传递，而附件上传要求认证上下文
                        // （记录 owner）。必须在请求链内部用 deferContextual 捕获——
                        // 脱链的裸 subscribe 读到的永远是空上下文——再把整个
                        // ContextView 写进任务链。
                        return Mono.deferContextual(ctxView -> {
                            job.setSubscription(petGeneratorService.generatePet(bytes, mediaType,
                                    finalName, finalStyle, withExpressions, customPrompt)
                                .contextWrite(ctxView)
                                .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                                    (java.util.function.Consumer<PetGenerationProgress>) job::progress))
                                .timeout(java.time.Duration.ofMinutes(25))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                    record -> job.finish(record.getId()),
                                    error -> {
                                        log.warn("宠物生成任务 {} 失败: {}", job.id,
                                            error.getMessage(), error);
                                        job.fail(error.getMessage());
                                    }));
                            return ServerResponse.ok().bodyValue(Map.of(
                                "success", true, "jobId", job.id, "job", job.toMap()));
                        });
                    });
            })
            .onErrorResume(e -> {
                log.warn("解析上传请求失败", e);
                return badRequest("请求格式错误：" + e.getMessage());
            });
    }

    private Mono<ServerResponse> handlePromptPreview(ServerRequest request) {
        return request.bodyToMono(PromptPreviewRequest.class)
            .defaultIfEmpty(new PromptPreviewRequest())
            .flatMap(body -> {
                String style = ALLOWED_STYLES.contains(body.getStyle())
                    ? body.getStyle() : "soft-3d";
                return ServerResponse.ok().bodyValue(Map.of(
                    "success", true,
                    "prompts", petGeneratorService.previewPrompts(style,
                        body.getCustomPrompt(), body.getExpressionPrompt(), body.getMode())));
            })
            .onErrorResume(e -> badRequest("提示词预览参数错误：" + e.getMessage()));
    }

    private Mono<ServerResponse> handleRegenerateMaster(ServerRequest request) {
        String id = request.pathVariable("id");
        return request.multipartData()
            .flatMap(parts -> {
                Part filePart = parts.getFirst("file");
                if (!(filePart instanceof FilePart file)) {
                    return badRequest("重新生成母版需要原始照片（字段名 file）");
                }
                String mediaType = file.headers().getContentType() != null
                    ? file.headers().getContentType().toString() : "";
                if (!ALLOWED_TYPES.contains(mediaType)) {
                    return badRequest("仅支持 JPEG / PNG / WebP 照片");
                }
                String style = formValue(parts, "style");
                if (!ALLOWED_STYLES.contains(style)) {
                    style = "soft-3d";
                }
                String finalStyle = style;
                String name = formValue(parts, "name");
                String customPrompt = formValue(parts, "customPrompt");
                return DataBufferUtils.join(file.content(), (int) MAX_PHOTO_BYTES)
                    .flatMap(buffer -> {
                        if (buffer.readableByteCount() > MAX_PHOTO_BYTES) {
                            DataBufferUtils.release(buffer);
                            return badRequest("照片不能超过 5MB");
                        }
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        PetJob job = new PetJob(UUID.randomUUID().toString());
                        jobs.add(job);
                        return Mono.deferContextual(ctxView -> {
                            job.setSubscription(petGeneratorService.regenerateMaster(id, bytes, mediaType,
                                    name, finalStyle, customPrompt)
                                .contextWrite(ctxView)
                                .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                                    (java.util.function.Consumer<PetGenerationProgress>) job::progress))
                                .timeout(java.time.Duration.ofMinutes(25))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                    record -> job.finish(record.getId()),
                                    error -> job.fail(error.getMessage())));
                            return ServerResponse.ok().bodyValue(Map.of(
                                "success", true, "jobId", job.id, "job", job.toMap()));
                        });
                    });
            })
            .onErrorResume(e -> badRequest("重新生成母版请求错误：" + e.getMessage()));
    }

    private Mono<ServerResponse> handleGetJob(ServerRequest request) {
        PetJob job = jobs.get(request.pathVariable("jobId"));
        if (job == null) {
            return ServerResponse.ok().bodyValue(Map.of(
                "success", false, "code", "JOB_GONE", "message", "任务不存在或已过期，请刷新宠物列表检查结果，避免重复生成"));
        }
        return ServerResponse.ok().bodyValue(Map.of("success", true, "job", job.toMap()));
    }

    private Mono<ServerResponse> handleList(ServerRequest request) {
        return petStore.list()
            .flatMap(pets -> ServerResponse.ok().bodyValue(Map.of(
                "success", true, "pets", pets)));
    }

    /**
     * 附件存储策略清单，供前端「宠物图存储策略」下拉使用。
     * 经插件自己的端点转发而不是让前端直调 Halo 原生 policies API——
     * 非超管管理员可能没有策略读权限。本地策略排最前，与上传兜底顺序一致。
     */
    private Mono<ServerResponse> handlePolicies(ServerRequest request) {
        return client.listAll(run.halo.app.core.extension.attachment.Policy.class,
                new run.halo.app.extension.ListOptions(),
                org.springframework.data.domain.Sort.unsorted())
            .sort(java.util.Comparator.comparingInt(p ->
                isLocalPolicy(p) ? 0 : 1))
            .map(policy -> {
                String name = policy.getMetadata().getName();
                var spec = policy.getSpec();
                Map<String, Object> item = new java.util.HashMap<>();
                item.put("name", name);
                item.put("displayName",
                    spec != null && spec.getDisplayName() != null ? spec.getDisplayName() : name);
                item.put("local", isLocalPolicy(policy));
                return item;
            })
            .collectList()
            .flatMap(policies -> ServerResponse.ok()
                .bodyValue(Map.of("success", true, "policies", policies)));
    }

    private boolean isLocalPolicy(run.halo.app.core.extension.attachment.Policy policy) {
        return policy.getSpec() != null
            && "local".equals(policy.getSpec().getTemplateName());
    }

    /** 内置皮肤清单（含预览图 URL），供后台选择器渲染 */
    private Mono<ServerResponse> handlePresets(ServerRequest request) {
        var presets = cn.rainwu.halo.ai.suite.service.BuiltinPets.list().stream()
            .map(pet -> Map.<String, Object>of(
                "name", pet.name(),
                "displayName", pet.displayName(),
                "preview", pet.imageUrl("idle"),
                "manifest", cn.rainwu.halo.ai.suite.service.BuiltinPets.manifest(pet)))
            .toList();
        return ServerResponse.ok().bodyValue(Map.of("success", true, "presets", presets));
    }

    private Mono<ServerResponse> handleDelete(ServerRequest request) {
        return petGeneratorService.deletePet(request.pathVariable("id"))
            .then(ServerResponse.ok().bodyValue(Map.of("success", true)));
    }

    private Mono<ServerResponse> handleRegenerate(ServerRequest request) {
        String id = request.pathVariable("id");
        Mono<ExpressionGenerationRequest> regionRequest =
            request.headers().contentLength().orElse(0L) == 0L
                ? Mono.just(new ExpressionGenerationRequest())
                : request.bodyToMono(ExpressionGenerationRequest.class)
                    .defaultIfEmpty(new ExpressionGenerationRequest());
        return regionRequest
            .flatMap(body -> {
                PetJob job = new PetJob(UUID.randomUUID().toString());
                job.total = 4;
                jobs.add(job);
                // 同 handleGenerate：在请求链内捕获 Reactor 上下文传给异步任务
                return Mono.deferContextual(ctxView -> {
                    job.setSubscription(petGeneratorService.regenerateExpressions(id, body.toRegion(),
                            body.getCustomPrompt())
                        .contextWrite(ctxView)
                                .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                                    (java.util.function.Consumer<PetGenerationProgress>) job::progress))
                        .timeout(java.time.Duration.ofMinutes(25))
                                .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                            record -> job.finish(record.getId()),
                            error -> job.fail(error.getMessage())));
                    return ServerResponse.ok().bodyValue(Map.of(
                        "success", true, "jobId", job.id, "job", job.toMap()));
                });
            })
            .onErrorResume(e -> badRequest("表情区域参数错误：" + e.getMessage()));
    }

    private Mono<ServerResponse> handleGenerateCandidates(ServerRequest request) {
        return request.bodyToMono(ExpressionGenerationRequest.class)
            .defaultIfEmpty(new ExpressionGenerationRequest())
            .flatMap(body -> Mono.deferContextual(context -> {
                PetJob job = new PetJob(UUID.randomUUID().toString());
                job.total = body.getState() == null ? 4 : 1;
                jobs.add(job);
                job.setSubscription(petGeneratorService.generateCandidateFrames(request.pathVariable("id"), body.getState(),
                        body.getMode(), body.toRegion(), body.getCustomPrompt())
                    .contextWrite(context)
                    .contextWrite(ctx -> ctx.put(PetGenerationProgress.CONTEXT_KEY,
                        (java.util.function.Consumer<PetGenerationProgress>) job::progress))
                    .timeout(java.time.Duration.ofMinutes(25))
                                .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(record -> job.finish(record.getId()), error -> job.fail(error.getMessage())));
                return ServerResponse.ok().bodyValue(Map.of("success", true, "jobId", job.id));
            })).onErrorResume(error -> badRequest("生成请求错误：" + error.getMessage()));
    }

    private Mono<ServerResponse> handleExpressionEdit(ServerRequest request) {
        return request.bodyToMono(CandidateReviewRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("请刷新后重新检查")))
            .flatMap(body -> petGeneratorService.beginExpressionEdit(request.pathVariable("id"), request.pathVariable("state"), body.getCandidateUrl()))
            .flatMap(pet -> ServerResponse.ok().bodyValue(Map.of("success", true, "pet", pet)))
            .onErrorResume(error -> badRequest(error.getMessage()));
    }

    private Mono<ServerResponse> handleEdges(ServerRequest request, boolean apply) {
        return request.bodyToMono(EdgeRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("请选择边缘优化参数")))
            .flatMap(body -> {
                if (apply) return petGeneratorService.applyEdges(request.pathVariable("id"), body.getState(), body.getUrl(), body.getRevision(), body.getShrink(), body.getDewhite())
                    .flatMap(pet -> ServerResponse.ok().bodyValue(Map.of("success", true, "pet", pet)));
                return petGeneratorService.previewEdges(request.pathVariable("id"), body.getState(), body.getUrl(), body.getRevision(), body.getShrink(), body.getDewhite())
                    .flatMap(bytes -> ServerResponse.ok().bodyValue(Map.of("success", true, "image", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes))));
            }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> badRequest(error.getMessage()));
    }

    @lombok.Data
    private static class EdgeRequest {
        private String state;
        private String url;
        private int revision;
        private int shrink;
        private int dewhite;
    }

    private Mono<ServerResponse> handleCandidateReview(ServerRequest request, String action) {
        return request.bodyToMono(CandidateReviewRequest.class).flatMap(body -> {
            String id = request.pathVariable("id");
            String state = request.pathVariable("state");
            Mono<PetStore.PetRecord> result = switch (action) {
                case "cleanup" -> petGeneratorService.cleanupCandidate(id, state, body.getCandidateUrl(), body.getStrokes(), body.getMask());
                case "cancel-edit" -> petGeneratorService.cancelExpressionEdit(id, state, body.getCandidateUrl());
                case "reset" -> petGeneratorService.resetCandidate(id, state, body.getCandidateUrl());
                default -> petGeneratorService.approveCandidate(id, state, body.getCandidateUrl());
            };
            return result.flatMap(record -> ServerResponse.ok().bodyValue(Map.of("success", true, "pet", record)));
        }).onErrorResume(error -> badRequest("候选图处理失败：" + error.getMessage()));
    }

    @lombok.Data
    private static class CandidateReviewRequest {
        private String candidateUrl;
        private List<PetStore.EraseStroke> strokes = List.of();
        private cn.rainwu.halo.ai.suite.service.ColorEraseMask mask;
    }

    private Mono<ServerResponse> handleCleanupBackground(ServerRequest request) {
        String id = request.pathVariable("id");
        return request.bodyToMono(BackgroundCleanupRequest.class)
            .defaultIfEmpty(new BackgroundCleanupRequest())
            .flatMap(body -> {
                if ((body.getStrokes() == null || body.getStrokes().isEmpty()) && body.getMask() == null) {
                    return badRequest("请先选择或涂抹需要清理的背景残留");
                }
                PetJob job = new PetJob(UUID.randomUUID().toString());
                jobs.add(job);
                return Mono.deferContextual(ctxView -> {
                    job.setSubscription(petGeneratorService.cleanupBackground(id, body.getStrokes(), body.getMasterUrl(), body.getMasterRevision(), body.getMask())
                        .contextWrite(ctxView)
                                .contextWrite(context -> context.put(PetGenerationProgress.CONTEXT_KEY,
                                    (java.util.function.Consumer<PetGenerationProgress>) job::progress))
                        .timeout(java.time.Duration.ofMinutes(25))
                                .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                            record -> job.finish(record.getId()),
                            error -> job.fail(error.getMessage())));
                    return ServerResponse.ok().bodyValue(Map.of(
                        "success", true, "jobId", job.id, "job", job.toMap()));
                });
            })
            .onErrorResume(e -> badRequest("背景残留清理请求错误：" + e.getMessage()));
    }

    private Mono<ServerResponse> handleApproveBackground(ServerRequest request) {
        return request.bodyToMono(BackgroundReviewRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("页面版本过旧，请刷新后重新检查背景")))
            .flatMap(body -> petGeneratorService.approveBackground(request.pathVariable("id"),
                body.getMasterUrl(), body.getMasterRevision()))
            .flatMap(record -> ServerResponse.ok().bodyValue(Map.of("success", true, "pet", record)))
            .onErrorResume(e -> badRequest("确认母版背景失败：" + e.getMessage()));
    }

    private Mono<ServerResponse> handleResetBackground(ServerRequest request) {
        return request.bodyToMono(BackgroundReviewRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("页面版本过旧，请刷新后重新检查背景")))
            .flatMap(body -> petGeneratorService.resetBackground(request.pathVariable("id"),
                body.getMasterUrl(), body.getMasterRevision()))
            .flatMap(record -> ServerResponse.ok().bodyValue(Map.of("success", true, "pet", record)))
            .onErrorResume(e -> badRequest("恢复原始母版失败：" + e.getMessage()));
    }

    @lombok.Data
    private static class BackgroundReviewRequest {
        private String masterUrl;
        private Integer masterRevision;
    }

    @lombok.Data
    private static final class PromptPreviewRequest {
        private String style = "soft-3d";
        private String customPrompt = "";
        private String expressionPrompt = "";
        private String mode = "face";
    }

    @lombok.Data
    private static final class ExpressionGenerationRequest {
        private double centerX = 0.5;
        private double centerY = 0.35;
        private double width = 0.48;
        private double height = 0.38;
        private double feather = 0.12;
        private String customPrompt = "";
        private String mode = "face";
        private String state;

        PetStore.ExpressionRegion toRegion() {
            PetStore.ExpressionRegion region = new PetStore.ExpressionRegion();
            region.setCenterX(centerX);
            region.setCenterY(centerY);
            region.setWidth(width);
            region.setHeight(height);
            region.setFeather(feather);
            return region;
        }
    }

    @lombok.Data
    @lombok.EqualsAndHashCode(callSuper = true)
    private static final class BackgroundCleanupRequest extends BackgroundReviewRequest {
        private List<PetStore.EraseStroke> strokes = List.of();
        private cn.rainwu.halo.ai.suite.service.ColorEraseMask mask;
    }

    private String formValue(MultiValueMap<String, Part> parts, String key) {
        Part part = parts.getFirst(key);
        return part instanceof FormFieldPart field ? field.value().trim() : "";
    }

    private Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.ok().bodyValue(Map.of("success", false, "message", message));
    }

    /** 生成任务状态：pending → done / failed。简单起见没有取消能力（生成本身无法中断）。 */
    static final class PetJob {
        private final String id;
        private final String createdAt = Instant.now().toString();
        private volatile String status = "pending";
        private volatile String petId = "";
        private volatile String error = "";
        private final long startedNanos = System.nanoTime();
        private long finishedNanos;
        private long finishedAt;
        private reactor.core.Disposable subscription;

        synchronized void setSubscription(reactor.core.Disposable subscription) {
            if (!"pending".equals(status)) subscription.dispose();
            else this.subscription = subscription;
        }

        synchronized void cancel() {
            var running = subscription;
            fail("服务已停止，任务已中断，请刷新宠物列表检查结果");
            if (running != null) running.dispose();
        }
        private int total = 1;
        private String stage = "preparing";
        private String currentState = "";
        private final java.util.LinkedHashSet<String> completedStates = new java.util.LinkedHashSet<>();

        synchronized void progress(PetGenerationProgress event) {
            if (!"pending".equals(status)) return;
            stage = event.stage();
            if (!event.state().isEmpty()) currentState = event.state();
            if ("frame-complete".equals(stage)) completedStates.add(event.state());
        }

        PetJob(String id) {
            this.id = id;
        }

        synchronized void finish(String petId) {
            if (!"pending".equals(status)) return;
            finishedNanos = System.nanoTime();
            finishedAt = System.currentTimeMillis();
            stage = "done";
            this.petId = petId;
            this.status = "done";
            this.subscription = null;
        }

        synchronized void fail(String message) {
            if (!"pending".equals(status)) return;
            finishedNanos = System.nanoTime();
            finishedAt = System.currentTimeMillis();
            this.error = message == null ? "未知错误" : message;
            this.status = "failed";
            this.subscription = null;
        }

        synchronized long finishedAt() { return finishedAt; }

        synchronized Map<String, Object> toMap() {
            return Map.of("id", id, "status", status, "petId", petId,
                "error", error, "createdAt", createdAt,
                "progress", Map.of("stage", stage, "currentState", currentState,
                    "completed", completedStates.size(), "total", total,
                    "completedStates", List.copyOf(completedStates),
                    "elapsedSeconds", (int) ((finishedNanos == 0 ? System.nanoTime() - startedNanos
                        : finishedNanos - startedNanos) / 1_000_000_000L)));
        }
    }
}

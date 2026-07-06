package cn.rainwu.halo.ai.suite.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import cn.rainwu.halo.ai.suite.service.MindMapService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleMindMapEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient extensionClient;
    private final MindMapService mindMapService;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, MindMapJob> jobs = new ConcurrentHashMap<>();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/mindmap/articles", this::handleArticles)
            .POST("/mindmap/generate", this::handleGenerate)
            .POST("/mindmap/regenerate", this::handleRegenerate)
            .POST("/mindmap/clear", this::handleClear)
            .POST("/mindmap/batch-generate", this::handleBatchGenerate)
            .POST("/mindmap/batch-clear", this::handleBatchClear)
            .POST("/mindmap/jobs/generate-all", this::handleStartGenerateAll)
            .GET("/mindmap/jobs/{jobId}", this::handleGetJob)
            .POST("/mindmap/jobs/{jobId}/cancel", this::handleCancelJob)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> handleArticles(ServerRequest request) {
        int page = safeInt(request.queryParam("page").orElse("1"), 1);
        int size = safeInt(request.queryParam("size").orElse("10"), 10);
        boolean asc = "asc".equals(request.queryParam("sort").orElse("desc"));
        String status = request.queryParam("status").orElse("all").trim();
        String keyword = request.queryParam("keyword").orElse("").trim().toLowerCase();

        return listPublishedPosts(keyword, asc)
            .flatMap(posts -> {
                return Flux.fromIterable(posts)
                    .concatMap(this::toItem)
                    .filter(item -> statusMatches(item, status))
                    .collectList()
                    .flatMap(items -> {
                        int total = items.size();
                        int from = Math.min(Math.max(0, (page - 1) * size), total);
                        int to = Math.min(from + size, total);
                        return ServerResponse.ok().bodyValue(Map.of(
                            "page", page,
                            "size", size,
                            "total", total,
                            "items", items.subList(from, to)
                        ));
                    });
            });
    }

    private Mono<List<Post>> listPublishedPosts(String keyword, boolean asc) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        return extensionClient.list(Post.class,
                post -> post.isPublished() && !post.isDeleted()
                    && post.getSpec() != null
                    && Boolean.TRUE.equals(post.getSpec().getPublish())
                    && (normalizedKeyword.isEmpty() || (post.getSpec().getTitle() != null
                    && post.getSpec().getTitle().toLowerCase().contains(normalizedKeyword))),
                null)
            .collectList()
            .doOnNext(posts -> posts.sort(publishTimeComparator(asc)));
    }

    private Comparator<Post> publishTimeComparator(boolean asc) {
        return (a, b) -> {
            var ta = a.getSpec().getPublishTime();
            var tb = b.getSpec().getPublishTime();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return asc ? ta.compareTo(tb) : tb.compareTo(ta);
        };
    }

    private Mono<Map<String, Object>> toItem(Post post) {
        return mindMapService.inspectCache(post).map(status -> Map.<String, Object>of(
            "postName", post.getMetadata().getName(),
            "title", post.getSpec().getTitle() != null ? post.getSpec().getTitle() : "",
            "publishTime", post.getSpec().getPublishTime() != null
                ? post.getSpec().getPublishTime().toString() : "",
            "hasMindMap", status.hasMindMap(),
            "stale", status.stale(),
            "status", status.status(),
            "markdown", status.markdown()
        ));
    }

    private boolean statusMatches(Map<String, Object> item, String status) {
        if (status == null || status.isBlank() || "all".equals(status)) return true;
        if ("generated".equals(status)) {
            return Boolean.TRUE.equals(item.get("hasMindMap"))
                && !Boolean.TRUE.equals(item.get("stale"));
        }
        if ("stale".equals(status)) {
            return Boolean.TRUE.equals(item.get("stale"));
        }
        if ("missing".equals(status)) {
            return !Boolean.TRUE.equals(item.get("hasMindMap"));
        }
        return true;
    }

    private Mono<ServerResponse> handleStartGenerateAll(ServerRequest request) {
        return request.bodyToMono(String.class)
            .defaultIfEmpty("{}")
            .map(this::parseScope)
            .flatMap(scope -> {
                boolean active = jobs.values().stream()
                    .anyMatch(job -> "pending".equals(job.status()) || "running".equals(job.status()));
                if (active) {
                    return ServerResponse.ok().bodyValue(Map.of(
                        "success", false,
                        "message", "已有脑图生成任务正在运行"
                    ));
                }

                MindMapJob job = new MindMapJob(UUID.randomUUID().toString(), scope);
                jobs.put(job.id(), job);
                runGenerateAllJob(job)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        null,
                        error -> failJob(job, error),
                        () -> finishJob(job)
                    );
                return ServerResponse.ok().bodyValue(Map.of(
                    "success", true,
                    "jobId", job.id(),
                    "job", job.toMap()
                ));
            })
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> handleGetJob(ServerRequest request) {
        MindMapJob job = jobs.get(request.pathVariable("jobId"));
        if (job == null) {
            return ServerResponse.ok().bodyValue(Map.of(
                "success", false,
                "message", "任务不存在或已过期"
            ));
        }
        return ServerResponse.ok().bodyValue(Map.of(
            "success", true,
            "job", job.toMap()
        ));
    }

    private Mono<ServerResponse> handleCancelJob(ServerRequest request) {
        MindMapJob job = jobs.get(request.pathVariable("jobId"));
        if (job == null) {
            return ServerResponse.ok().bodyValue(Map.of(
                "success", false,
                "message", "任务不存在或已过期"
            ));
        }
        job.cancel();
        return ServerResponse.ok().bodyValue(Map.of(
            "success", true,
            "job", job.toMap()
        ));
    }

    private Mono<Void> runGenerateAllJob(MindMapJob job) {
        job.phase("collecting", "收集文章列表");
        return listPublishedPosts("", false)
            .flatMapMany(posts -> {
                job.total(posts.size());
                if (posts.isEmpty()) {
                    job.phase("done", "没有可处理的文章");
                    return Flux.empty();
                }
                job.phase("inspecting", "检查脑图缓存状态");
                return Flux.fromIterable(posts);
            })
            .concatMap(post -> processPostInJob(job, post))
            .then();
    }

    private Mono<Void> processPostInJob(MindMapJob job, Post post) {
        if (job.cancelled()) {
            return Mono.empty();
        }
        String postName = post.getMetadata().getName();
        String title = post.getSpec() != null && post.getSpec().getTitle() != null
            ? post.getSpec().getTitle() : postName;
        job.current(postName, title);
        job.phase("checking", "检查缓存状态");

        return mindMapService.inspectCache(post)
            .flatMap(cache -> {
                if (shouldSkip(job.scope(), cache)) {
                    job.skipped(title, postName, skipReason(cache));
                    return Mono.empty();
                }

                job.phase("reading", "读取文章内容");
                Mono<MindMapService.MindMapResult> generate = "all".equals(job.scope())
                    ? mindMapService.clearCache(postName).then(mindMapService.getOrGenerate(postName))
                    : mindMapService.getOrGenerate(postName);

                job.phase("generating", "调用模型生成脑图");
                return generate
                    .doOnSuccess(result -> {
                        job.phase("saving", "保存脑图缓存");
                        job.success(title, postName, result.cached());
                    })
                    .then();
            })
            .onErrorResume(error -> {
                job.failure(title, postName, error.getMessage());
                return Mono.empty();
            });
    }

    private String parseScope(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String scope = root.path("scope").asText("missing_stale");
            return switch (scope) {
                case "missing", "missing_stale", "all" -> scope;
                default -> "missing_stale";
            };
        } catch (Exception e) {
            return "missing_stale";
        }
    }

    private boolean shouldSkip(String scope, MindMapService.MindMapCacheStatus cache) {
        if ("all".equals(scope)) return false;
        if ("missing".equals(scope)) return cache.hasMindMap();
        return cache.hasMindMap() && !cache.stale();
    }

    private String skipReason(MindMapService.MindMapCacheStatus cache) {
        if (cache.hasMindMap() && !cache.stale()) return "已生成且未过期";
        return "不在生成范围内";
    }

    private void finishJob(MindMapJob job) {
        if ("cancelled".equals(job.status())) {
            return;
        }
        job.status("completed");
        job.current("", "");
        job.phase("done", "任务完成");
        job.finishedAt(Instant.now().toString());
    }

    private void failJob(MindMapJob job, Throwable error) {
        job.status("failed");
        job.phase("failed", error.getMessage() != null ? error.getMessage() : "任务失败");
        job.finishedAt(Instant.now().toString());
        log.warn("[ConsoleMindMapEndpoint] 脑图生成任务失败: {}", error.getMessage());
    }

    private Mono<ServerResponse> handleGenerate(ServerRequest request) {
        return parsePostName(request)
            .flatMap(postName -> mindMapService.getOrGenerate(postName)
                .flatMap(result -> ServerResponse.ok().bodyValue(Map.of(
                    "success", true,
                    "postName", postName,
                    "markdown", result.markdown(),
                    "cached", result.cached()
                ))))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> handleRegenerate(ServerRequest request) {
        return parsePostName(request)
            .flatMap(postName -> mindMapService.clearCache(postName)
                .then(mindMapService.getOrGenerate(postName))
                .flatMap(result -> ServerResponse.ok().bodyValue(Map.of(
                    "success", true,
                    "postName", postName,
                    "markdown", result.markdown(),
                    "cached", false
                ))))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> handleClear(ServerRequest request) {
        return parsePostName(request)
            .flatMap(postName -> mindMapService.clearCache(postName)
                .then(ServerResponse.ok().bodyValue(Map.of(
                    "success", true,
                    "postName", postName
                ))))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> handleBatchGenerate(ServerRequest request) {
        return parsePostNames(request)
            .flatMap(postNames -> Flux.fromIterable(postNames)
                .concatMap(postName -> mindMapService.getOrGenerate(postName)
                    .map(result -> Map.<String, Object>of(
                        "postName", postName,
                        "success", true,
                        "cached", result.cached()))
                    .onErrorResume(e -> Mono.just(Map.<String, Object>of(
                        "postName", postName,
                        "success", false,
                        "error", e.getMessage()))))
                .collectList()
                .flatMap(results -> {
                    long ok = results.stream()
                        .filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
                    return ServerResponse.ok().bodyValue(Map.of(
                        "success", true,
                        "count", ok,
                        "total", results.size(),
                        "results", results
                    ));
                }))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> handleBatchClear(ServerRequest request) {
        return parsePostNames(request)
            .flatMap(postNames -> Flux.fromIterable(postNames)
                .concatMap(postName -> mindMapService.clearCache(postName)
                    .thenReturn(Map.<String, Object>of("postName", postName, "success", true))
                    .onErrorResume(e -> Mono.just(Map.<String, Object>of(
                        "postName", postName,
                        "success", false,
                        "error", e.getMessage()))))
                .collectList()
                .flatMap(results -> {
                    long ok = results.stream()
                        .filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
                    return ServerResponse.ok().bodyValue(Map.of(
                        "success", true,
                        "count", ok,
                        "total", results.size(),
                        "results", results
                    ));
                }))
            .onErrorResume(this::badRequest);
    }

    private Mono<String> parsePostName(ServerRequest request) {
        return request.bodyToMono(String.class).map(body -> {
            try {
                JsonNode root = objectMapper.readTree(body);
                String postName = root.path("postName").asText("");
                if (postName.isBlank()) {
                    throw new IllegalArgumentException("postName 不能为空");
                }
                return postName;
            } catch (Exception e) {
                throw new IllegalArgumentException("请求解析失败: " + e.getMessage());
            }
        });
    }

    private Mono<List<String>> parsePostNames(ServerRequest request) {
        return request.bodyToMono(String.class).map(body -> {
            try {
                JsonNode root = objectMapper.readTree(body);
                List<String> postNames = new ArrayList<>();
                root.path("postNames").forEach(n -> {
                    String value = n.asText("");
                    if (!value.isBlank()) postNames.add(value);
                });
                if (postNames.isEmpty()) {
                    throw new IllegalArgumentException("postNames 不能为空");
                }
                return postNames;
            } catch (Exception e) {
                throw new IllegalArgumentException("请求解析失败: " + e.getMessage());
            }
        });
    }

    private Mono<ServerResponse> badRequest(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : "操作失败";
        log.warn("[ConsoleMindMapEndpoint] {}", message);
        return ServerResponse.ok()
            .bodyValue(Map.of("success", false, "message", message));
    }

    private int safeInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class MindMapJob {
        private final String id;
        private final String scope;
        private final String createdAt = Instant.now().toString();
        private final List<Map<String, Object>> results =
            Collections.synchronizedList(new ArrayList<>());
        private volatile String status = "pending";
        private volatile String phase = "pending";
        private volatile String phaseText = "准备生成";
        private volatile String currentPostName = "";
        private volatile String currentTitle = "";
        private volatile String finishedAt = "";
        private volatile boolean cancelled = false;
        private volatile int total = 0;
        private volatile int completed = 0;
        private volatile int success = 0;
        private volatile int skipped = 0;
        private volatile int failed = 0;

        MindMapJob(String id, String scope) {
            this.id = id;
            this.scope = scope;
        }

        String id() {
            return id;
        }

        String scope() {
            return scope;
        }

        String status() {
            return status;
        }

        boolean cancelled() {
            return cancelled;
        }

        void status(String status) {
            this.status = status;
        }

        void total(int total) {
            this.total = total;
            this.status = total > 0 ? "running" : "completed";
        }

        void current(String postName, String title) {
            this.currentPostName = postName;
            this.currentTitle = title;
        }

        void phase(String phase, String phaseText) {
            this.phase = phase;
            this.phaseText = phaseText;
        }

        void finishedAt(String finishedAt) {
            this.finishedAt = finishedAt;
        }

        void cancel() {
            this.cancelled = true;
            this.status = "cancelled";
            this.phase = "cancelled";
            this.phaseText = "任务已取消";
            this.finishedAt = Instant.now().toString();
        }

        void success(String title, String postName, boolean cached) {
            this.completed++;
            this.success++;
            this.results.add(Map.of(
                "postName", postName,
                "title", title,
                "status", "success",
                "message", cached ? "读取缓存" : "生成完成"
            ));
        }

        void skipped(String title, String postName, String reason) {
            this.completed++;
            this.skipped++;
            this.results.add(Map.of(
                "postName", postName,
                "title", title,
                "status", "skipped",
                "message", reason
            ));
        }

        void failure(String title, String postName, String message) {
            this.completed++;
            this.failed++;
            this.results.add(Map.of(
                "postName", postName,
                "title", title,
                "status", "failed",
                "message", message == null ? "生成失败" : message
            ));
        }

        Map<String, Object> toMap() {
            int percent = total <= 0 ? 0 : Math.min(100, Math.round(completed * 100f / total));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("scope", scope);
            data.put("status", status);
            data.put("phase", phase);
            data.put("phaseText", phaseText);
            data.put("currentPostName", currentPostName);
            data.put("currentTitle", currentTitle);
            data.put("total", total);
            data.put("completed", completed);
            data.put("success", success);
            data.put("skipped", skipped);
            data.put("failed", failed);
            data.put("percent", percent);
            data.put("createdAt", createdAt);
            data.put("finishedAt", finishedAt);
            data.put("results", List.copyOf(results));
            return data;
        }
    }
}

package run.halo.ai.assistant.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.rag.LuceneIndexService;
import run.halo.ai.assistant.rag.ReindexService;
import run.halo.ai.assistant.service.SummaryService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 API — 管理端接口，需要管理员权限
 *
 * - POST   /knowledge/reindex            全量重建索引
 * - GET    /knowledge/stats              索引统计
 * - GET    /knowledge/sidebar-stats      侧栏徽章聚合统计 (chunk count + indexed + retrieval/enhancement enabled)
 * - GET    /knowledge/articles           文章索引状态列表
 * - GET    /knowledge/articles/{name}/chunks  文章切片预览
 * - POST   /knowledge/reindex-post/{name}     重试单篇文章索引
 * - POST   /knowledge/summarize          为指定文章生成摘要
 * - POST   /knowledge/summarize-all      为所有文章生成摘要并保存
 * - GET    /knowledge/excerpts           查询已生成摘要的文章列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleKnowledgeEndpoint implements CustomEndpoint {

    private final ReindexService reindexService;
    private final LuceneIndexService luceneIndexService;
    private final SummaryService summaryService;
    private final ReactiveExtensionClient extensionClient;
    private final AIProperties aiProperties;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
            .POST("/knowledge/reindex", this::handleReindex)
            .GET("/knowledge/reindex/progress", this::handleReindexProgress)
            .GET("/knowledge/stats", this::handleStats)
            .GET("/knowledge/sidebar-stats", this::handleSidebarStats)
            .GET("/knowledge/articles", this::handleArticles)
            .GET("/knowledge/articles/{name}/chunks", this::handleArticleChunks)
            .POST("/knowledge/reindex-post/{name}", this::handleReindexPost)
            .GET("/knowledge/reindex-post/{name}/progress", this::handleReindexPostProgress)
            .POST("/knowledge/clear-post/{name}", this::handleClearPost)
            .POST("/knowledge/summarize", this::handleSummarize)
            .POST("/knowledge/summarize-all", this::handleSummarizeAll)
            .GET("/knowledge/excerpts", this::handleExcerptList)
            .GET("/knowledge/excerpts/all", this::handleExcerptAll)
            .POST("/knowledge/excerpts/generate", this::handleExcerptGenerate)
            .POST("/knowledge/excerpts/toggle-auto", this::handleExcerptToggleAuto)
            .POST("/knowledge/excerpts/clear", this::handleExcerptClear)
            .POST("/knowledge/excerpts/batch-generate", this::handleExcerptBatchGenerate)
            .POST("/knowledge/excerpts/batch-clear", this::handleExcerptBatchClear)
            .POST("/knowledge/excerpts/clear-all", this::handleExcerptClearAll)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-assistant.halo.run", "v1alpha1");
    }

    // ===== 全量重建索引 =====

    private Mono<ServerResponse> handleReindex(ServerRequest request) {
        return reindexService.startReindexAsync()
            .then(ServerResponse.ok()
                .bodyValue(Map.of("status", "started", "message", "索引重建已启动")))
            .onErrorResume(e -> {
                log.error("[KnowledgeEndpoint] 启动重建失败: {}", e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
                return ServerResponse.ok()
                    .bodyValue(Map.of(
                        "success", false,
                        "status", msg.contains("重建中") ? "already_indexing" : "error",
                        "message", msg
                    ));
            });
    }

    private Mono<ServerResponse> handleReindexProgress(ServerRequest request) {
        Flux<ServerSentEvent<String>> sseStream = reindexService.progressStream()
            .map(progress -> {
                try {
                    String json = objectMapper.writeValueAsString(progress);
                    return ServerSentEvent.<String>builder()
                        .event("progress")
                        .data(json)
                        .build();
                } catch (Exception e) {
                    return ServerSentEvent.<String>builder()
                        .event("error")
                        .data("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
                }
            })
            .onErrorResume(e -> Flux.just(
                ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"error\":\"" + e.getMessage() + "\"}")
                    .build()
            ));

        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(sseStream, ServerSentEvent.class);
    }

    // ===== 侧栏徽章聚合统计 (admin 端用) =====
    // 返回切片/索引/增强启用数 三个数字 — 前端侧栏徽章用
    private Mono<ServerResponse> handleSidebarStats(ServerRequest request) {
        Mono<Integer> chunks = Mono.fromCallable(() -> luceneIndexService.getDocumentCount())
            .subscribeOn(Schedulers.boundedElastic());
        Mono<Integer> indexed = Mono.fromCallable(() -> luceneIndexService.getPostChunkCounts().size())
            .subscribeOn(Schedulers.boundedElastic());
        Mono<Integer> failed = Mono.fromCallable(() -> reindexService.getFailedPosts().size())
            .subscribeOn(Schedulers.boundedElastic());
        Mono<AIProperties.EnhancementConfig> enh = aiProperties.getEnhancementConfig()
            .defaultIfEmpty(new AIProperties.EnhancementConfig());

        return Mono.zip(chunks, indexed, failed, enh)
            .flatMap(t -> {
                int chunkCount = t.getT1();
                int indexedArticles = t.getT2();
                int failedArticles = t.getT3();
                AIProperties.EnhancementConfig enhCfg = t.getT4();

                // 检索增强 = 启用的增强项数 (queryRewrite + hyde + rerank + crossLanguage)
                int enhancementActive = (enhCfg.isQueryRewriteToggle() ? 1 : 0)
                    + (enhCfg.isHydeEnabled() ? 1 : 0)
                    + (enhCfg.isRerankToggle() ? 1 : 0)
                    + (enhCfg.isCrossLanguageEnabled() ? 1 : 0);

                return ServerResponse.ok().bodyValue(Map.of(
                    "chunks", chunkCount,
                    "indexed", indexedArticles,
                    "failed", failedArticles,
                    "enhancementActive", enhancementActive,
                    "enhancementTotal", 4
                ));
            });
    }

    // ===== 索引统计（增强版）=====

    private Mono<ServerResponse> handleStats(ServerRequest request) {
        return Mono.fromCallable(() -> {
                Map<String, Integer> chunkCounts = luceneIndexService.getPostChunkCounts();
                return Map.<String, Object>of(
                    "chunkCount", luceneIndexService.getDocumentCount(),
                    "status", reindexService.isIndexing() ? "indexing" : "idle",
                    "indexedArticles", chunkCounts.size(),
                    "failedArticles", reindexService.getFailedPosts().size(),
                    "keywordChunks", luceneIndexService.countKeywordChunks()
                );
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(stats -> extensionClient.list(Post.class,
                    post -> post.isPublished()
                        && !post.isDeleted()
                        && post.getSpec() != null
                        && Boolean.TRUE.equals(post.getSpec().getPublish())
                        && Post.isPublic(post.getSpec()),
                    null)
                .collectList()
                .map(posts -> {
                    Map<String, Object> body = new LinkedHashMap<>(stats);
                    body.put("totalArticles", posts.size());
                    return body;
                }))
            .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    // ===== 文章索引状态列表 =====

    private Mono<ServerResponse> handleArticles(ServerRequest request) {
        return Mono.fromCallable(() -> {
                var chunkCounts = luceneIndexService.getPostChunkCounts();
                var keywordCounts = luceneIndexService.getPostKeywordChunkCounts();
                return Map.of("chunkCounts", chunkCounts, "keywordCounts", keywordCounts);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(counts ->
                extensionClient.list(Post.class,
                        post -> post.isPublished()
                            && !post.isDeleted()
                            && post.getSpec() != null
                            && Boolean.TRUE.equals(post.getSpec().getPublish())
                            && Post.isPublic(post.getSpec()),
                        null)
                    .collectList()
                    .map(posts -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Integer> chunkCounts = (Map<String, Integer>) counts.get("chunkCounts");
                        @SuppressWarnings("unchecked")
                        Map<String, Integer> keywordCounts = (Map<String, Integer>) counts.get("keywordCounts");
                        Map<String, String> failedPosts = reindexService.getFailedPosts();
                        List<Map<String, Object>> articles = posts.stream().map(post -> {
                            String postName = post.getMetadata().getName();
                            String title = post.getSpec().getTitle();
                            Integer chunkCount = chunkCounts.get(postName);

                            String status;
                            if (failedPosts.containsKey(postName)) {
                                status = "failed";
                            } else if (chunkCount != null) {
                                status = "indexed";
                            } else {
                                status = "not_indexed";
                            }

                            Map<String, Object> article = new LinkedHashMap<>();
                            article.put("postName", postName);
                            article.put("title", title);
                            article.put("status", status);
                            article.put("chunkCount", chunkCount != null ? chunkCount : 0);
                            article.put("keywordChunks", keywordCounts.getOrDefault(postName, 0));
                            article.put("keywordStatus", reindexService.getKeywordStatus().getOrDefault(postName, ""));
                            article.put("keywordTruncated", reindexService.getKeywordTruncated().getOrDefault(postName, 0));
                            article.put("createTime", post.getMetadata().getCreationTimestamp() != null
                                ? post.getMetadata().getCreationTimestamp().toString() : "");
                            article.put("updateTime", post.getStatus() != null && post.getStatus().getLastModifyTime() != null
                                ? post.getStatus().getLastModifyTime().toString() : "");
                            return article;
                        }).toList();

                        long indexed = articles.stream()
                            .filter(a -> "indexed".equals(a.get("status"))).count();
                        long failed = articles.stream()
                            .filter(a -> "failed".equals(a.get("status"))).count();

                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("articles", articles);
                        body.put("totalArticles", articles.size());
                        body.put("indexedArticles", indexed);
                        body.put("failedArticles", failed);
                        body.put("notIndexedArticles", articles.size() - indexed - failed);
                        return body;
                    })
            )
            .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    // ===== 文章切片预览 =====

    private Mono<ServerResponse> handleArticleChunks(ServerRequest request) {
        String postName = request.pathVariable("name");
        return Mono.fromCallable(() -> luceneIndexService.getChunksByPostId(postName))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(chunks -> ServerResponse.ok()
                .bodyValue(Map.of("chunks", chunks, "postName", postName)))
            .onErrorResume(e -> ServerResponse.ok()
                .bodyValue(Map.of("chunks", List.of(), "postName", postName)));
    }

    // ===== 重试单篇文章索引 =====

    private Mono<ServerResponse> handleReindexPost(ServerRequest request) {
        String postName = request.pathVariable("name");
        return reindexService.reindexPost(postName)
            .flatMap(chunkCount -> ServerResponse.ok()
                .bodyValue(Map.of(
                    "success", true,
                    "chunkCount", chunkCount,
                    "message", "索引完成，共 " + chunkCount + " 个切片"
                )))
            .onErrorResume(e -> {
                log.error("[KnowledgeEndpoint] 单篇索引失败 {}: {}", postName, e.getMessage());
                return ServerResponse.ok()
                    .bodyValue(Map.of(
                        "success", false,
                        "message", "索引失败: " + e.getMessage()
                    ));
            });
    }

    // ===== 单篇重建索引进度 (SSE) =====

    private Mono<ServerResponse> handleReindexPostProgress(ServerRequest request) {
        String postName = request.pathVariable("name");
        var sink = reindexService.reindexPostWithProgress(postName);

        Flux<ServerSentEvent<String>> sseStream = sink.asFlux()
            .map(data -> ServerSentEvent.<String>builder()
                .event("progress")
                .data(data)
                .build());

        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(sseStream, ServerSentEvent.class);
    }

    // ===== 清除单篇文章索引 =====

    private Mono<ServerResponse> handleClearPost(ServerRequest request) {
        String postName = request.pathVariable("name");
        return reindexService.deletePostIndex(postName)
            .then(ServerResponse.ok()
                .bodyValue(Map.of(
                    "success", true,
                    "message", "索引已清除"
                )))
            .onErrorResume(e -> {
                log.error("[KnowledgeEndpoint] 清除索引失败 {}: {}", postName, e.getMessage());
                return ServerResponse.ok()
                    .bodyValue(Map.of(
                        "success", false,
                        "message", "清除失败: " + e.getMessage()
                    ));
            });
    }

    // ===== 摘要生成 =====

    private Mono<ServerResponse> handleSummarize(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    String postName = root.path("postName").asText("");
                    if (postName.isBlank()) {
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("success", false, "message", "postName 不能为空"));
                    }
                    return summaryService.generateSummary(postName)
                        .flatMap(summary -> ServerResponse.ok()
                            .bodyValue(Map.of("success", true, "summary", summary)));
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("success", false, "message", "请求解析失败: " + e.getMessage()));
                }
            });
    }

    private Mono<ServerResponse> handleSummarizeAll(ServerRequest request) {
        return summaryService.generateAndSaveAllSummaries()
            .collectList()
            .flatMap(results -> {
                long savedCount = results.stream().filter(r -> Boolean.TRUE.equals(r.get("saved"))).count();
                return ServerResponse.ok()
                    .bodyValue(Map.of(
                        "success", true,
                        "count", savedCount,
                        "total", results.size(),
                        "results", results
                    ));
            })
            .onErrorResume(e -> {
                log.error("[KnowledgeEndpoint] 批量摘要生成失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .bodyValue(Map.of("success", false, "message", "生成失败: " + e.getMessage()));
            });
    }

    // ===== 已生成摘要的文章列表 =====

    private Mono<ServerResponse> handleExcerptList(ServerRequest request) {
        int page = Integer.parseInt(request.queryParam("page").orElse("1"));
        int size = Integer.parseInt(request.queryParam("size").orElse("10"));
        return extensionClient.list(Post.class,
                post -> post.isPublished() && !post.isDeleted()
                    && post.getSpec() != null
                    && post.getSpec().getExcerpt() != null
                    && post.getSpec().getExcerpt().getRaw() != null
                    && !post.getSpec().getExcerpt().getRaw().isBlank(),
                null)
            .collectList()
            .map(posts -> {
                int total = posts.size();
                int from = Math.min((page - 1) * size, total);
                int to = Math.min(from + size, total);
                var paged = posts.subList(from, to);

                List<Map<String, Object>> items = paged.stream()
                    .map(post -> Map.<String, Object>of(
                        "postName", post.getMetadata().getName(),
                        "title", post.getSpec().getTitle() != null ? post.getSpec().getTitle() : "",
                        "excerpt", post.getSpec().getExcerpt().getRaw()
                    ))
                    .toList();

                return Map.of(
                    "page", page,
                    "size", size,
                    "total", total,
                    "items", items
                );
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    // ===== 全量文章摘要管理 =====

    private Mono<ServerResponse> handleExcerptAll(ServerRequest request) {
        int page = Integer.parseInt(request.queryParam("page").orElse("1"));
        int size = Integer.parseInt(request.queryParam("size").orElse("20"));
        boolean asc = "asc".equals(request.queryParam("sort").orElse("desc"));
        final String keyword = request.queryParam("keyword").orElse("").trim();
        final String q = keyword.toLowerCase();
        return extensionClient.list(Post.class,
                post -> post.isPublished() && !post.isDeleted()
                    && post.getSpec() != null
                    && Boolean.TRUE.equals(post.getSpec().getPublish())
                    && (q.isEmpty() || (post.getSpec().getTitle() != null
                        && post.getSpec().getTitle().toLowerCase().contains(q))),
                null)
            .collectList()
            .map(posts -> {
                // 服务端排序
                posts.sort((a, b) -> {
                    var ta = a.getSpec().getPublishTime();
                    var tb = b.getSpec().getPublishTime();
                    if (ta == null && tb == null) return 0;
                    if (ta == null) return 1;
                    if (tb == null) return -1;
                    return asc ? ta.compareTo(tb) : tb.compareTo(ta);
                });
                int total = posts.size();
                int from = Math.min((page - 1) * size, total);
                int to = Math.min(from + size, total);
                var paged = posts.subList(from, to);

                List<Map<String, Object>> items = paged.stream()
                    .map(post -> {
                        var excerpt = post.getSpec().getExcerpt();
                        boolean hasExcerpt = excerpt != null && excerpt.getRaw() != null && !excerpt.getRaw().isBlank();
                        boolean autoGenerate = excerpt != null && Boolean.TRUE.equals(excerpt.getAutoGenerate());
                        return Map.<String, Object>of(
                            "postName", post.getMetadata().getName(),
                            "title", post.getSpec().getTitle() != null ? post.getSpec().getTitle() : "",
                            "excerpt", hasExcerpt ? excerpt.getRaw() : "",
                            "hasExcerpt", hasExcerpt,
                            "autoGenerate", autoGenerate,
                            "publishTime", post.getSpec().getPublishTime() != null
                                ? post.getSpec().getPublishTime().toString() : ""
                        );
                    })
                    .toList();

                return Map.of(
                    "page", page,
                    "size", size,
                    "total", total,
                    "items", items
                );
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> handleExcerptGenerate(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    String postName = root.path("postName").asText("");
                    if (postName.isBlank()) {
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("success", false, "message", "postName 不能为空"));
                    }
                    return summaryService.generateAndSave(postName)
                        .flatMap(summary -> ServerResponse.ok()
                            .bodyValue(Map.of("success", true, "summary", summary, "postName", postName)));
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("success", false, "message", "请求解析失败: " + e.getMessage()));
                }
            });
    }

    private Mono<ServerResponse> handleExcerptToggleAuto(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    String postName = root.path("postName").asText("");
                    boolean enabled = root.path("enabled").asBoolean(false);
                    if (postName.isBlank()) {
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("success", false, "message", "postName 不能为空"));
                    }
                    return extensionClient.fetch(Post.class, postName)
                        .flatMap(post -> {
                            var excerpt = post.getSpec().getExcerpt();
                            if (excerpt == null) {
                                excerpt = new Post.Excerpt();
                                post.getSpec().setExcerpt(excerpt);
                            }
                            excerpt.setAutoGenerate(enabled);
                            return extensionClient.update(post);
                        })
                        .then(ServerResponse.ok()
                            .bodyValue(Map.of("success", true, "enabled", enabled, "postName", postName)));
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("success", false, "message", "请求解析失败: " + e.getMessage()));
                }
            });
    }

    private Mono<ServerResponse> handleExcerptClear(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    String postName = root.path("postName").asText("");
                    if (postName.isBlank()) {
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("success", false, "message", "postName 不能为空"));
                    }
                    return extensionClient.fetch(Post.class, postName)
                        .flatMap(post -> {
                            var excerpt = new Post.Excerpt();
                            excerpt.setAutoGenerate(false);
                            excerpt.setRaw("");
                            post.getSpec().setExcerpt(excerpt);
                            return extensionClient.update(post);
                        })
                        .then(ServerResponse.ok()
                            .bodyValue(Map.of("success", true, "postName", postName)));
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("success", false, "message", "请求解析失败: " + e.getMessage()));
                }
            });
    }

    // ===== 批量操作 =====

    private Mono<ServerResponse> handleExcerptBatchGenerate(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    List<String> postNames = new ArrayList<>();
                    root.path("postNames").forEach(n -> postNames.add(n.asText()));
                    if (postNames.isEmpty()) {
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("success", false, "message", "postNames 不能为空"));
                    }
                    return Flux.fromIterable(postNames)
                        .flatMap(name -> summaryService.generateAndSave(name)
                            .map(summary -> Map.<String, Object>of("postName", name, "success", true, "summary", summary))
                            .onErrorResume(e -> Mono.just(Map.<String, Object>of("postName", name, "success", false, "error", e.getMessage()))))
                        .collectList()
                        .flatMap(results -> {
                            long ok = results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
                            return ServerResponse.ok().bodyValue(Map.of("success", true, "count", ok, "total", results.size(), "results", results));
                        });
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("success", false, "message", "请求解析失败: " + e.getMessage()));
                }
            });
    }

    private Mono<ServerResponse> handleExcerptBatchClear(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    List<String> postNames = new ArrayList<>();
                    root.path("postNames").forEach(n -> postNames.add(n.asText()));
                    if (postNames.isEmpty()) {
                        return ServerResponse.badRequest()
                            .bodyValue(Map.of("success", false, "message", "postNames 不能为空"));
                    }
                    return Flux.fromIterable(postNames)
                        .flatMap(name -> extensionClient.fetch(Post.class, name)
                            .flatMap(post -> {
                                post.getSpec().setExcerpt(null);
                                return extensionClient.update(post);
                            })
                            .map(p -> Map.<String, Object>of("postName", name, "success", true))
                            .onErrorResume(e -> Mono.just(Map.<String, Object>of("postName", name, "success", false, "error", e.getMessage()))))
                        .collectList()
                        .flatMap(results -> {
                            long ok = results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
                            return ServerResponse.ok().bodyValue(Map.of("success", true, "count", ok, "total", results.size(), "results", results));
                        });
                } catch (Exception e) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("success", false, "message", "请求解析失败: " + e.getMessage()));
                }
            });
    }

    private Mono<ServerResponse> handleExcerptClearAll(ServerRequest request) {
        return extensionClient.list(Post.class,
                post -> post.isPublished() && !post.isDeleted()
                    && post.getSpec() != null
                    && post.getSpec().getExcerpt() != null
                    && post.getSpec().getExcerpt().getRaw() != null
                    && !post.getSpec().getExcerpt().getRaw().isBlank(),
                null)
            .flatMap(post -> {
                post.getSpec().setExcerpt(null);
                return extensionClient.update(post);
            })
            .count()
            .flatMap(count -> ServerResponse.ok()
                .bodyValue(Map.of("success", true, "count", count)));
    }
}

package run.halo.ai.assistant.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.assistant.rag.LuceneIndexService;
import run.halo.ai.assistant.rag.ReindexService;
import run.halo.ai.assistant.service.SummaryService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 API — 管理端接口，需要管理员权限
 *
 * - POST   /knowledge/reindex            全量重建索引
 * - GET    /knowledge/stats              索引统计
 * - GET    /knowledge/articles           文章索引状态列表
 * - GET    /knowledge/articles/{name}/chunks  文章切片预览
 * - POST   /knowledge/reindex-post/{name}     重试单篇文章索引
 * - POST   /knowledge/summarize          为指定文章生成摘要
 * - POST   /knowledge/summarize-all      为所有文章生成摘要
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleKnowledgeEndpoint implements CustomEndpoint {

    private final ReindexService reindexService;
    private final LuceneIndexService luceneIndexService;
    private final SummaryService summaryService;
    private final ReactiveExtensionClient extensionClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
            .POST("/knowledge/reindex", this::handleReindex)
            .GET("/knowledge/stats", this::handleStats)
            .GET("/knowledge/articles", this::handleArticles)
            .GET("/knowledge/articles/{name}/chunks", this::handleArticleChunks)
            .POST("/knowledge/reindex-post/{name}", this::handleReindexPost)
            .POST("/knowledge/summarize", this::handleSummarize)
            .POST("/knowledge/summarize-all", this::handleSummarizeAll)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-assistant.halo.run", "v1alpha1");
    }

    // ===== 全量重建索引 =====

    private Mono<ServerResponse> handleReindex(ServerRequest request) {
        return reindexService.reindexAllWithDetails()
            .flatMap(result -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", result.failedPosts() == 0);
                body.put("chunkCount", result.totalChunks());
                body.put("successPosts", result.successPosts());
                body.put("failedPosts", result.failedPosts());
                if (result.failedPosts() > 0) {
                    body.put("failedTitles", result.failedTitles());
                    body.put("message", "索引重建完成，共 " + result.totalChunks() + " 个切片，"
                        + result.failedPosts() + " 篇文章失败");
                } else {
                    body.put("message", "索引重建完成，共 " + result.totalChunks() + " 个切片");
                }
                return ServerResponse.ok().bodyValue(body);
            })
            .onErrorResume(e -> {
                log.error("[KnowledgeEndpoint] 全量重建失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .bodyValue(Map.of(
                        "success", false,
                        "message", "重建失败: " + e.getMessage()
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
                    "failedArticles", reindexService.getFailedPosts().size()
                );
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    // ===== 文章索引状态列表 =====

    private Mono<ServerResponse> handleArticles(ServerRequest request) {
        return Mono.fromCallable(luceneIndexService::getPostChunkCounts)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(chunkCounts ->
                extensionClient.list(Post.class,
                        post -> post.isPublished()
                            && !post.isDeleted()
                            && post.getSpec() != null
                            && Boolean.TRUE.equals(post.getSpec().getPublish())
                            && Post.isPublic(post.getSpec()),
                        null)
                    .collectList()
                    .map(posts -> {
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
        return summaryService.generateAllSummaries()
            .collectList()
            .flatMap(results -> ServerResponse.ok()
                .bodyValue(Map.of(
                    "success", true,
                    "count", results.size(),
                    "results", results
                )))
            .onErrorResume(e -> {
                log.error("[KnowledgeEndpoint] 批量摘要生成失败: {}", e.getMessage());
                return ServerResponse.ok()
                    .bodyValue(Map.of("success", false, "message", "生成失败: " + e.getMessage()));
            });
    }
}

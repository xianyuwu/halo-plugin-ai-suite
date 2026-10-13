package cn.rainwu.halo.ai.suite.endpoint;

import cn.rainwu.halo.ai.suite.service.TagGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;

/** Console-only article tag generation and application API. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsoleTagEndpoint implements CustomEndpoint {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ReactiveExtensionClient extensionClient;
    private final TagGenerationService tagGenerationService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/tags/articles", this::articles)
            .POST("/tags/generate", this::generate)
            .POST("/tags/apply", this::apply)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> articles(ServerRequest request) {
        int page = boundedInt(request.queryParam("page").orElse("1"), 1, 1, 100000);
        int size = boundedInt(request.queryParam("size").orElse("10"), 10, 1, 50);
        String keyword = request.queryParam("keyword").orElse("").trim();
        ListOptions.ListOptionsBuilder options = ListOptions.builder()
            .andQuery(Queries.equal("spec.deleted", "false"))
            .andQuery(Queries.isNull("metadata.deletionTimestamp"));
        if (!keyword.isEmpty()) {
            options.andQuery(Queries.contains("spec.title", keyword));
        }
        Sort sort = Sort.by(Sort.Order.desc("metadata.creationTimestamp"),
            Sort.Order.asc("metadata.name"));
        return extensionClient.listBy(Post.class, options.build(),
            PageRequestImpl.of(page, size, sort)).flatMap(result -> {
            List<Post> posts = result.getItems();
            Set<String> tagIds = new LinkedHashSet<>();
            posts.forEach(post -> {
                if (post.getSpec().getTags() != null) tagIds.addAll(post.getSpec().getTags());
            });
            Mono<List<Tag>> pageTags = tagIds.isEmpty() ? Mono.just(List.of())
                : extensionClient.listAll(Tag.class, ListOptions.builder()
                    .andQuery(Queries.in("metadata.name", new ArrayList<Object>(tagIds)))
                    .build(), Sort.unsorted()).collectList();
            return pageTags.flatMap(tags -> {
                Map<String, String> names = new LinkedHashMap<>();
                for (Tag tag : tags) {
                    if (tag.getSpec() != null) {
                        names.put(tag.getMetadata().getName(), tag.getSpec().getDisplayName());
                    }
                }
                List<Map<String, Object>> items = new ArrayList<>();
                for (Post post : posts) {
                    var spec = post.getSpec();
                    List<String> tagNames = spec.getTags() == null ? List.of()
                        : spec.getTags().stream().map(id -> names.getOrDefault(id, id)).toList();
                    items.add(Map.of(
                        "postName", post.getMetadata().getName(),
                        "title", spec.getTitle() == null ? "" : spec.getTitle(),
                        "tags", tagNames,
                        "published", post.isPublished(),
                        "createdAt", post.getMetadata().getCreationTimestamp() == null
                            ? "" : post.getMetadata().getCreationTimestamp().toString()
                    ));
                }
                return ServerResponse.ok().bodyValue(Map.of(
                    "page", page, "size", size, "total", result.getTotal(), "items", items));
            });
        });
    }

    private Mono<ServerResponse> generate(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                JsonNode root;
                try { root = MAPPER.readTree(body); }
                catch (Exception e) { return badRequest("请求格式无效"); }
                String postName = root.path("postName").asText("").trim();
                if (postName.isEmpty()) return badRequest("请选择文章");
                int count = boundedInt(root.path("count").asText("5"), 5, 1, 8);
                return tagGenerationService.generate(postName, count)
                    .flatMap(result -> ServerResponse.ok().bodyValue(result))
                    .onErrorResume(error -> {
                        log.warn("AI 标签生成失败: post={}, type={}, message={}", postName,
                            error.getClass().getSimpleName(), error.getMessage());
                        return failure(error);
                    });
            });
    }

    private Mono<ServerResponse> apply(ServerRequest request) {
        return request.bodyToMono(String.class)
            .flatMap(body -> {
                JsonNode root;
                try { root = MAPPER.readTree(body); }
                catch (Exception e) { return badRequest("请求格式无效"); }
                String postName = root.path("postName").asText("").trim();
                JsonNode tagsNode = root.path("tags");
                if (postName.isEmpty() || !tagsNode.isArray()) return badRequest("文章或标签无效");
                List<String> tags = new ArrayList<>();
                tagsNode.forEach(node -> { if (node.isTextual()) tags.add(node.asText()); });
                return tagGenerationService.apply(postName, tags)
                    .flatMap(result -> ServerResponse.ok().bodyValue(result))
                    .onErrorResume(this::failure);
            });
    }

    private Mono<ServerResponse> failure(Throwable error) {
        HttpStatus status = error instanceof IllegalArgumentException
            ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = error instanceof IllegalArgumentException
            ? error.getMessage() : "AI 标签操作失败，请检查模型配置或稍后重试";
        return ServerResponse.status(status).bodyValue(Map.of("message", message));
    }

    private Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.badRequest().bodyValue(Map.of("message", message));
    }

    private static int boundedInt(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
        catch (Exception ignored) { return fallback; }
    }
}

package cn.rainwu.halo.ai.suite.endpoint;

import cn.rainwu.halo.ai.suite.service.TagGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import run.halo.app.extension.ReactiveExtensionClient;

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
        String keyword = request.queryParam("keyword").orElse("").trim().toLowerCase(Locale.ROOT);
        return Mono.zip(
            extensionClient.list(Post.class, post -> !post.isDeleted()
                && post.getSpec() != null
                && (keyword.isEmpty() || (post.getSpec().getTitle() != null
                    && post.getSpec().getTitle().toLowerCase(Locale.ROOT).contains(keyword))), null)
                .collectList(),
            extensionClient.list(Tag.class, tag -> true, null).collectList()
        ).flatMap(tuple -> {
            List<Post> posts = tuple.getT1();
            Map<String, String> names = new LinkedHashMap<>();
            for (Tag tag : tuple.getT2()) {
                if (tag.getSpec() != null) {
                    names.put(tag.getMetadata().getName(), tag.getSpec().getDisplayName());
                }
            }
            posts.sort(Comparator.comparing(
                (Post post) -> post.getMetadata().getCreationTimestamp(),
                Comparator.nullsLast(Comparator.reverseOrder())));
            int total = posts.size();
            int from = (int) Math.min((long) (page - 1) * size, total);
            int to = Math.min(from + size, total);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Post post : posts.subList(from, to)) {
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
                "page", page, "size", size, "total", total, "items", items));
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

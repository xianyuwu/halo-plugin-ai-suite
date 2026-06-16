package run.halo.ai.suite.endpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.service.MindMapService;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.Map;

/**
 * 思维导图公开 API — 访客只读取已生成且未过期的缓存。
 *
 * <p>GET /mindmap?postName=xxx — 获取已缓存思维导图。
 * <p>生成、重生成和清缓存只允许走 Console API，避免匿名用户触发 LLM 成本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicMindMapEndpoint implements CustomEndpoint {

    private final MindMapService mindMapService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/mindmap", this::handleGetMindMap)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.ai-suite.halo.run", "v1alpha1");
    }

    /**
     * GET /mindmap?postName=xxx
     *
     * 返回格式: { "markdown": "...", "cached": true/false }
     * 错误格式: { "error": "..." }
     */
    private Mono<ServerResponse> handleGetMindMap(ServerRequest request) {
        String postName = request.queryParam("postName").orElse("").trim();
        if (postName.isEmpty()) {
            return ServerResponse.ok()
                .bodyValue(Map.of("error", "postName 参数必填"));
        }

        return mindMapService.getCachedForPublic(postName)
            .flatMap(result -> ServerResponse.ok()
                .bodyValue(Map.of(
                    "markdown", result.markdown(),
                    "cached", result.cached()
                )))
            .onErrorResume(e -> {
                String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
                log.error("[MindMap] 生成失败: postName={}, {}", postName, msg);

                // 文章不存在（Halo NotFoundException / 404）
                if (msg.contains("not found") || msg.contains("NotFound")
                        || msg.contains("404") || e.getClass().getSimpleName().contains("NotFound")) {
                    return ServerResponse.ok()
                        .bodyValue(Map.of("error", "文章不存在"));
                }
                // 内容为空
                if (msg.contains("为空")) {
                    return ServerResponse.ok()
                        .bodyValue(Map.of("error", "文章内容为空"));
                }
                // 功能未启用
                if (msg.contains("未启用")) {
                    return ServerResponse.ok()
                        .bodyValue(Map.of("error", "思维导图功能未启用"));
                }
                return ServerResponse.ok()
                    .bodyValue(Map.of("error", "生成失败：" + msg));
            });
    }
}

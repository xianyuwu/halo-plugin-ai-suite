package cn.rainwu.halo.ai.suite.endpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 访客聊天兼容热更新端点。
 *
 * <p>Halo 在不重启主进程时可能继续保留旧版 v1alpha1 RouterFunction。
 * 使用独立 API 版本可以避开旧路由，使新增的推理配置与请求参数在插件重载后立即生效。</p>
 */
@Component
@RequiredArgsConstructor
public class PublicChatV1alpha2Endpoint implements CustomEndpoint {

    private final PublicChatEndpoint delegate;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .POST("/chat/stream", delegate::handleStreamChatPost)
            .POST("/chat", delegate::handleChatPost)
            .GET("/widget-config", delegate::handleWidgetConfig)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.ai-suite.halo.run", "v1alpha2");
    }
}

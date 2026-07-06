package cn.rainwu.halo.ai.suite.intent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;

/**
 * Post 查询工具 — 提供所有处理器的「获取已发布文章」公共逻辑.
 * <p>
 * 把"过滤已发布 + 公开 + 未删除"的条件统一抽出来，避免 6 个处理器重复写.
 * 复用项目里现成的过滤条件范式（见 {@code ContentGapAgentService.java:60-65}、
 * {@code ChatService.java:473-478}）.
 */
@Component
@RequiredArgsConstructor
public class PostQuerySupport {

    private final ReactiveExtensionClient client;

    /**
     * 列出所有已发布、公开、未删除的文章.
     * <p>切到 {@code boundedElastic} 是因为 ReactiveExtensionClient.list
     * 在大规模数据下接近阻塞 I/O（参考项目内其它调用点的处理）.
     */
    public Mono<List<Post>> listPublishedPublicPosts() {
        return client.list(Post.class,
                post -> post.isPublished()
                    && !post.isDeleted()
                    && post.getSpec() != null
                    && Boolean.TRUE.equals(post.getSpec().getPublish())
                    && Post.isPublic(post.getSpec()),
                null)
            .collectList()
            .subscribeOn(Schedulers.boundedElastic());
    }
}

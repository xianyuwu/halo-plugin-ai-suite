package run.halo.ai.assistant.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.ai.assistant.rag.ReindexService;
import run.halo.app.event.post.PostDeletedEvent;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.event.post.PostUnpublishedEvent;
import run.halo.app.event.post.PostUpdatedEvent;

/**
 * 文章变更监听器 — 自动同步 Lucene 索引
 *
 * 监听 Halo 的文章生命周期事件：
 * - 发布 → 索引文章
 * - 更新 → 重新索引
 * - 取消发布/删除 → 删除索引
 *
 * 事件处理是异步的（不影响文章操作本身），失败只记日志不阻塞。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostChangeListener {

    private final ReindexService reindexService;

    @EventListener
    public void onPostPublished(PostPublishedEvent event) {
        String postName = event.getName();
        log.info("[PostChangeListener] 文章发布: {}", postName);
        reindexService.reindexPost(postName)
            .doOnSuccess(count -> log.debug("[PostChangeListener] 索引完成: {} ({} chunks)", postName, count))
            .doOnError(e -> log.error("[PostChangeListener] 索引失败: {} - {}", postName, e.getMessage()))
            .subscribe();
    }

    @EventListener
    public void onPostUpdated(PostUpdatedEvent event) {
        String postName = event.getName();
        log.info("[PostChangeListener] 文章更新: {}", postName);
        reindexService.reindexPost(postName)
            .doOnSuccess(count -> log.debug("[PostChangeListener] 重新索引完成: {} ({} chunks)", postName, count))
            .doOnError(e -> log.error("[PostChangeListener] 重新索引失败: {} - {}", postName, e.getMessage()))
            .subscribe();
    }

    @EventListener
    public void onPostUnpublished(PostUnpublishedEvent event) {
        String postName = event.getName();
        log.info("[PostChangeListener] 文章取消发布: {}", postName);
        reindexService.deletePostIndex(postName)
            .doOnError(e -> log.error("[PostChangeListener] 删除索引失败: {} - {}", postName, e.getMessage()))
            .subscribe();
    }

    @EventListener
    public void onPostDeleted(PostDeletedEvent event) {
        String postName = event.getPost().getMetadata().getName();
        log.info("[PostChangeListener] 文章删除: {}", postName);
        reindexService.deletePostIndex(postName)
            .doOnError(e -> log.error("[PostChangeListener] 删除索引失败: {} - {}", postName, e.getMessage()))
            .subscribe();
    }
}

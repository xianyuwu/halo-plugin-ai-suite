package run.halo.ai.assistant.listener;

import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.ai.assistant.rag.ReindexService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.controller.Reconciler.Request;

/**
 * Post 向量索引 reconciler —— 文章发布/更新时自动重建 Lucene 索引。
 * <p>
 * 由 Halo 的 {@code PluginControllerManager} 通过 {@code SpringPluginStartedEvent}
 * 自动发现并启动；不需要额外的 ExtensionDefinition YAML。
 * <p>
 * 行为：
 * <ul>
 *   <li>Post 变更触发 reconcile；未发布/已删除/私有文章会清理索引（不重建）</li>
 *   <li>同一篇文章 60s 内不重复触发（防抖，避免频繁调 embedding API）</li>
 *   <li>所有异常都被吞掉并记录日志，不让 controller 死掉</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostIndexReconciler implements Reconciler<Request> {

    private static final long DEBOUNCE_MILLIS = 60_000L;

    private final ReactiveExtensionClient extensionClient;
    private final ReindexService reindexService;

    /** postName -> 上次成功 reindex 的 epoch millis */
    private final ConcurrentHashMap<String, Long> lastIndexedAt = new ConcurrentHashMap<>();

    @Override
    public Result reconcile(Request request) {
        String name = request.name();
        try {
            var postOpt = extensionClient.fetch(Post.class, name).block();
            if (postOpt == null) {
                // Post 已被删除 —— 不需要重试，Halo 不会再 enqueue
                return Result.doNotRetry();
            }
            Post post = postOpt;
            if (isNotIndexable(post)) {
                // 未发布/已删除/私有 → 清理索引（reindexPost 内部已处理）
                reindexService.reindexPost(name).block();
                return Result.doNotRetry();
            }
            long now = System.currentTimeMillis();
            Long last = lastIndexedAt.get(name);
            if (last != null && now - last < DEBOUNCE_MILLIS) {
                log.debug("[PostIndexReconciler] 文章 {} 防抖中（{}ms 前已处理）",
                    name, now - last);
                return Result.doNotRetry();
            }
            Integer count = reindexService.reindexPost(name).block();
            lastIndexedAt.put(name, System.currentTimeMillis());
            log.info("[PostIndexReconciler] 文章 {} 索引完成：{} 个 chunk", name, count);
            return Result.doNotRetry();
        } catch (Exception e) {
            // 不让 controller 死掉；记日志，下次 reconcile 再尝试
            log.warn("[PostIndexReconciler] 文章 {} 索引失败: {}",
                name, e.getMessage());
            return Result.doNotRetry();
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        // 启动时把全量 Post enqueue 一次（未发布/已删除/私有在 reconcile 内部会被跳过）
        return builder.extension(new Post())
            .syncAllListOptions(ListOptions.builder().build())
            .build();
    }

    private static boolean isNotIndexable(Post post) {
        if (post.isDeleted() || post.getSpec() == null) {
            return true;
        }
        if (!Boolean.TRUE.equals(post.getSpec().getPublish())) {
            return true;
        }
        // Post.isPublic() 检查可见性，私有文章不索引
        return !Post.isPublic(post.getSpec());
    }
}

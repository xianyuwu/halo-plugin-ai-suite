package cn.rainwu.halo.ai.suite.listener;

import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import cn.rainwu.halo.ai.suite.rag.ReindexService;
import run.halo.app.core.extension.content.Post;
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
     *   <li>插件启动 / reload 时不主动 syncAll，避免每次 reload 都重建所有文章索引</li>
 *   <li>相同正文与索引配置的重复事件通过持久化指纹幂等跳过</li>
 *   <li>临时异常最多自动重试 3 次，避免无限模型调用</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostIndexReconciler implements Reconciler<Request> {

    private static final int MAX_AUTO_RETRIES = 3;

    private final ReindexService reindexService;

    /** postName -> 当前连续失败次数（进程内有界重试） */
    private final ConcurrentHashMap<String, Integer> retryAttempts = new ConcurrentHashMap<>();

    @Override
    public Result reconcile(Request request) {
        String name = request.name();
        try {
            ReindexService.PostReindexResult result =
                reindexService.reindexPostIfChanged(name).block();
            retryAttempts.remove(name);
            if (result != null && result.skipped()) {
                log.debug("[PostIndexReconciler] 文章 {} 无索引相关变化，已跳过", name);
            } else {
                int count = result != null ? result.chunkCount() : 0;
                log.info("[PostIndexReconciler] 文章 {} 索引完成：{} 个 chunk", name, count);
            }
            return Result.doNotRetry();
        } catch (Exception e) {
            int attempt = retryAttempts.merge(name, 1, Integer::sum);
            if (attempt >= MAX_AUTO_RETRIES) {
                retryAttempts.remove(name);
                log.error("[PostIndexReconciler] 文章 {} 自动索引连续失败 {} 次，"
                    + "已停止自动重试，请在索引中心手动重试: {}",
                    name, attempt, e.getMessage());
                return Result.doNotRetry();
            }
            log.warn("[PostIndexReconciler] 文章 {} 索引失败（{}/{}），30 秒后重试: {}",
                name, attempt, MAX_AUTO_RETRIES, e.getMessage());
            return Result.requeue(java.time.Duration.ofSeconds(30));
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        // Halo 默认 syncAllOnStart=true；必须显式关闭，否则插件 reload
        // 会把所有旧 Post 作为 onAdd 事件入队，导致全站重建和模型费用。
        // 文章新增/更新/删除仍会由 controller watch 事件触发 reconcile。
        return builder.extension(new Post())
            .syncAllOnStart(false)
            .build();
    }
}

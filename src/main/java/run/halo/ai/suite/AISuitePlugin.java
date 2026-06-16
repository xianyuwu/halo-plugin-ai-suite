package run.halo.ai.suite;

import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.ai.suite.extension.ChatLog;
import run.halo.ai.suite.extension.AgentTaskRecord;
import run.halo.ai.suite.extension.EvaluationDataset;
import run.halo.ai.suite.extension.EvaluationRunRecord;
import run.halo.ai.suite.rag.LuceneIndexService;

/**
 * 插件主类 —— 提供 Halo 插件生命周期钩子。
 * <p>
 * 历史说明：早期版本曾在 start() 里跑 10s 轮询兜底处理 autoGenerate 摘要，
 * 因 Halo 2.24 的 #10039 bug。升级到含 #10042 修复的版本后，
 * 摘要生成走 Halo 自带的 ExcerptGenerator 扩展点即可（参见
 * {@code service.AIExcerptGenerator}），轮询路径已删除。
 * <p>
 * 向量索引的自动触发由 {@code listener.PostIndexReconciler} 接管，
 * 走 Halo 的 Reconciler 机制，插件启动时会被 PluginControllerManager 自动发现。
 * <p>
 * 启动时同时注册 {@link ChatLog}, {@link AgentTaskRecord}, {@link EvaluationDataset} 和
 * {@link EvaluationRunRecord} Extension (CRD).
 */
@Slf4j
@Component
public class AISuitePlugin extends BasePlugin {

    @Autowired
    private SchemeManager schemeManager;

    @Autowired
    private LuceneIndexService luceneIndexService;

    public AISuitePlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("[AI Suite] 插件启动");
        // 修复 Halo 框架 bug：插件 stop/start 循环时 classloader 改变，
        // 但 DefaultSchemeManager.register() 用 Scheme.equals(GVK) 去重，
        // 第二次 register 会被跳过，导致 IndicesManager 里的 ChatLog class
        // 是旧 classloader 的，client.create() 用新 classloader 找不到。
        // 解决：start() 时先 unregister 旧的 Scheme，再 register 新的。
        unregisterStaleScheme("ChatLog", "ai-assistant.halo.run");
        unregisterStaleScheme("AgentTaskRecord", "ai-assistant.halo.run");
        unregisterStaleScheme("EvaluationDataset", "ai-assistant.halo.run");
        unregisterStaleScheme("EvaluationRunRecord", "ai-assistant.halo.run");

        // 注册 ChatLog CRD + 字段索引 (timestamp/feedbackType/model)
        schemeManager.register(ChatLog.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<ChatLog, Instant>single("spec.timestamp", Instant.class)
                .indexFunc(log -> log.getSpec() != null ? log.getSpec().getTimestamp() : null));
            indexSpecs.add(IndexSpecs.<ChatLog, String>single("spec.feedbackType", String.class)
                .indexFunc(log -> log.getSpec() != null ? log.getSpec().getFeedbackType() : null));
            indexSpecs.add(IndexSpecs.<ChatLog, String>single("spec.model", String.class)
                .indexFunc(log -> log.getSpec() != null ? log.getSpec().getModel() : null));
        });

        // 注册 AgentTaskRecord CRD + 字段索引 (createdAt/taskType/status)
        schemeManager.register(AgentTaskRecord.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<AgentTaskRecord, Instant>single("spec.createdAt", Instant.class)
                .indexFunc(record -> record.getSpec() != null ? record.getSpec().getCreatedAt() : null));
            indexSpecs.add(IndexSpecs.<AgentTaskRecord, String>single("spec.taskType", String.class)
                .indexFunc(record -> record.getSpec() != null ? record.getSpec().getTaskType() : null));
            indexSpecs.add(IndexSpecs.<AgentTaskRecord, String>single("spec.status", String.class)
                .indexFunc(record -> record.getSpec() != null ? record.getSpec().getStatus() : null));
        });

        // 注册 EvaluationDataset CRD + 字段索引 (defaultDataset/updatedAt)
        schemeManager.register(EvaluationDataset.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<EvaluationDataset, Boolean>single("spec.defaultDataset", Boolean.class)
                .indexFunc(dataset -> dataset.getSpec() != null
                    ? Boolean.TRUE.equals(dataset.getSpec().getDefaultDataset()) : false));
            indexSpecs.add(IndexSpecs.<EvaluationDataset, Instant>single("spec.updatedAt", Instant.class)
                .indexFunc(dataset -> dataset.getSpec() != null ? dataset.getSpec().getUpdatedAt() : null));
        });

        // 注册 EvaluationRunRecord CRD + 字段索引 (startedAt/datasetId)
        schemeManager.register(EvaluationRunRecord.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<EvaluationRunRecord, Instant>single("spec.startedAt", Instant.class)
                .indexFunc(record -> record.getSpec() != null ? record.getSpec().getStartedAt() : null));
            indexSpecs.add(IndexSpecs.<EvaluationRunRecord, String>single("spec.datasetId", String.class)
                .indexFunc(record -> record.getSpec() != null ? record.getSpec().getDatasetId() : null));
        });
    }

    private void unregisterStaleScheme(String kind, String group) {
        List<Scheme> staleSchemes = schemeManager.schemes().stream()
            .filter(s -> s.groupVersionKind().kind().equals(kind)
                && s.groupVersionKind().group().equals(group))
            .toList();
        staleSchemes.forEach(scheme -> {
                log.info("[AI Suite] 检测到旧 {}/{} Scheme, 强制 unregister", group, kind);
                schemeManager.unregister(scheme);
            });
    }

    @Override
    public void stop() {
        log.info("[AI Suite] 插件停止");
        // 不 unregister 数据类扩展 — 数据类扩展应跨重启持久化，
        // unregister 会让 Halo 框架把所有实例标记为待删除，GC 随后清理
        // 显式关闭 Lucene IndexWriter 释放 JVM 全局锁（LOCK_HELD），否则插件 reload 后
        // 旧锁残留 → 新 IndexWriter 撞 "Lock held by this virtual machine" → 索引不可用、被迫重启 Halo。
        try {
            luceneIndexService.close();
        } catch (Exception e) {
            log.warn("[AI Suite] 关闭 Lucene 索引失败: {}", e.getMessage());
        }
    }
}

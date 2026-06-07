package run.halo.ai.assistant;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.ai.assistant.extension.ChatLog;

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
 * 启动时同时注册 {@link ChatLog} Extension (CRD) — 访客问答日志
 * 用 Halo 原生对象存储取代旧的 ConfigMap JSON 数组方案.
 */
@Slf4j
@Component
public class AIAssistantPlugin extends BasePlugin {

    @Autowired
    private SchemeManager schemeManager;

    public AIAssistantPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("[AI Assistant] 插件启动");
        // 修复 Halo 框架 bug：插件 stop/start 循环时 classloader 改变，
        // 但 DefaultSchemeManager.register() 用 Scheme.equals(GVK) 去重，
        // 第二次 register 会被跳过，导致 IndicesManager 里的 ChatLog class
        // 是旧 classloader 的，client.create() 用新 classloader 找不到。
        // 解决：start() 时先 unregister 旧的 Scheme，再 register 新的。
        schemeManager.schemes().stream()
            .filter(s -> s.groupVersionKind().kind().equals("ChatLog")
                && s.groupVersionKind().group().equals("ai-assistant.halo.run"))
            .findFirst()
            .ifPresent(scheme -> {
                log.info("[AI Assistant] 检测到旧 ChatLog Scheme, 强制 unregister");
                schemeManager.unregister(scheme);
            });
        // 注册 ChatLog CRD + 字段索引 (timestamp/feedbackType/model)
        schemeManager.register(ChatLog.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<ChatLog, Instant>single("spec.timestamp", Instant.class)
                .indexFunc(log -> log.getSpec() != null ? log.getSpec().getTimestamp() : null));
            indexSpecs.add(IndexSpecs.<ChatLog, String>single("spec.feedbackType", String.class)
                .indexFunc(log -> log.getSpec() != null ? log.getSpec().getFeedbackType() : null));
            indexSpecs.add(IndexSpecs.<ChatLog, String>single("spec.model", String.class)
                .indexFunc(log -> log.getSpec() != null ? log.getSpec().getModel() : null));
        });
    }

    @Override
    public void stop() {
        log.info("[AI Assistant] 插件停止");
        // 不 unregister ChatLog — 数据类扩展应跨重启持久化，
        // unregister 会让 Halo 框架把所有 ChatLog 实例标记为待删除，GC 随后清理
    }
}


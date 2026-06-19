package run.halo.ai.suite.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 意图路由配置 — 定义一条「自定义问答意图」.
 * <p>
 * 当用户问题命中 {@link Spec#getTriggerPatterns()} 时，跳过 RAG 流程，改走
 * {@link Spec#getPipeline()} 配置的处理器链（如「LLM 标题推理 → 标签过滤 → 时间排序」），
 * 最终把结果交给 LLM 组织成自然语言回答。
 * <p>
 * 设计上与现有 {@code HOT_ARTICLES} 硬编码意图对齐：每个 IntentRoute 对应一条
 * pipeline，{@code hot-articles} 作为内置模板由 {@code IntentRouteService} 预置，
 * 替代原硬编码分支。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "ai-suite.halo.run",
    version = "v1alpha1",
    kind = "IntentRoute",
    plural = "intentroutes",
    singular = "intentroute"
)
public class IntentRoute extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "IntentRouteSpec")
    public static class Spec {

        @Schema(description = "展示名称")
        private String displayName;

        @Schema(description = "意图说明")
        private String description;

        @Schema(description = "是否启用")
        private Boolean enabled;

        @Schema(description = "多意图冲突时的优先级（数字越大越优先）")
        private Integer priority;

        @Schema(description = "是否内置（内置项不可删除，可编辑）")
        private Boolean builtin;

        @Schema(description = "触发关键词正则列表（OR 关系，任一命中即触发）")
        private List<String> triggerPatterns;

        @Schema(description = "正则都没命中时是否让 LLM 兜底分类")
        private Boolean llmFallback;

        @Schema(description = "给 LLM 兜底分类器的语义提示")
        private String llmFallbackHint;

        @Schema(description = "有序处理器步骤")
        private List<PipelineStep> pipeline;

        @Schema(description = "LLM 组织回答时的输出格式模板（可空）")
        private String outputTemplate;

        @Schema(description = "更新时间")
        private Instant updatedAt;
    }

    @Data
    @Schema(name = "IntentRoutePipelineStep")
    public static class PipelineStep {

        @Schema(description = "处理器类型，对应 PipelineProcessor.type()")
        private String type;

        @Schema(description = "处理器参数（key-value 字符串，避免深层嵌套序列化问题）")
        private Map<String, String> params;
    }
}

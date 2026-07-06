package cn.rainwu.halo.ai.suite.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 运营智能体任务记录 — 保存一次 Agent 运行结果.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "ai-suite.halo.run",
    version = "v1alpha1",
    kind = "AgentTaskRecord",
    plural = "agenttaskrecords",
    singular = "agenttaskrecord"
)
public class AgentTaskRecord extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "AgentTaskRecordSpec")
    public static class Spec {
        private String taskId;
        private String taskType;
        private String status;
        private String summary;
        private String error;
        private Integer progress;
        private String currentStep;
        private Instant createdAt;
        private Instant completedAt;
        private Long durationMs;
        private String inputJson;
        private String metricsJson;
        private String reportJson;
        private String stepsJson;
    }
}

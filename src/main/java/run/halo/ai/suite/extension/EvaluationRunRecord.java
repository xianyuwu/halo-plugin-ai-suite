package run.halo.ai.suite.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 效果评测运行记录 — 保存一次完整评测报告.
 *
 * <p>summary 用 @Data 类（Halo Extension 需要 getter/setter），
 * results 存为 JSON 字符串避免嵌套 record 验证失败。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "ai-assistant.halo.run",
    version = "v1alpha1",
    kind = "EvaluationRunRecord",
    plural = "evaluationrunrecords",
    singular = "evaluationrunrecord"
)
public class EvaluationRunRecord extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "EvaluationRunRecordSpec")
    public static class Spec {
        private String runId;
        private String datasetId;
        private String displayName;
        private Instant startedAt;
        private Long durationMs;
        private EvaluationSummary summary;
        private String resultsJson;
    }

    @Data
    @Schema(name = "EvaluationRunSummary")
    public static class EvaluationSummary {
        private int totalCases;
        private double avgScore;
        private double relevance;
        private double correctness;
        private double faithfulness;
        private double completeness;
        private double citationAccuracy;
        private double retrievalHitRate;
        private double hallucinationHighRiskRate;
        private long failedCases;
    }
}

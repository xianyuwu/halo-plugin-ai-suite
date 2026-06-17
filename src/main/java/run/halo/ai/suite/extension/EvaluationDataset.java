package run.halo.ai.suite.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 效果评测集 — 保存一组可重复运行的评测用例.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "ai-suite.halo.run",
    version = "v1alpha1",
    kind = "EvaluationDataset",
    plural = "evaluationdatasets",
    singular = "evaluationdataset"
)
public class EvaluationDataset extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "EvaluationDatasetSpec")
    public static class Spec {
        @Schema(description = "评测集显示名称")
        private String displayName;

        @Schema(description = "评测集说明")
        private String description;

        @Schema(description = "是否为默认评测集")
        private Boolean defaultDataset;

        @Schema(description = "更新时间")
        private Instant updatedAt;

        @Schema(description = "评测用例")
        private List<Case> cases;
    }

    @Data
    @Schema(name = "EvaluationDatasetCase")
    public static class Case {
        private String id;
        private String question;
        private String referenceAnswer;
        private List<String> expectedSources;
        private List<String> tags;
    }
}

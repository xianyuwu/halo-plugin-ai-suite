package cn.rainwu.halo.ai.suite.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 访客问答日志 — Halo Extension (CRD) 形式.
 *
 * <p>每条记录独立存储为 Halo extension 对象, 通过 {@code metadata.name} 作为
 * 唯一 ID (原 ChatLogEntry.id 字段). 旧 ConfigMap JSON 数组存储已废弃,
 * 启动时一次性迁移 (见 {@code AISuitePlugin.start()}).
 *
 * <p>可索引字段 (注册时声明):
 * <ul>
 *   <li>{@code spec.timestamp} — 用于分页排序 + 时间范围过滤</li>
 *   <li>{@code spec.feedbackType} — 派生自 feedback.type, "like" / "dislike" / "none", 用于按反馈过滤</li>
 *   <li>{@code spec.model} — 按模型过滤</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "ai-suite.halo.run",
    version = "v1alpha1",
    kind = "ChatLog",
    plural = "chatlogs",
    singular = "chatlog"
)
public class ChatLog extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "ChatLogSpec")
    public static class Spec {
        @Schema(description = "记录时间 (UTC)")
        private Instant timestamp;

        @Schema(description = "访客 IP")
        private String ip;

        @Schema(description = "访客 User-Agent")
        private String userAgent;

        @Schema(description = "用户提问")
        private String question;

        @Schema(description = "AI 回答")
        private String answer;

        @Schema(description = "使用的聊天模型")
        private String model;

        @Schema(description = "引用来源列表 (RAG 检索结果)")
        private List<Map<String, String>> citations;

        @Schema(description = "反馈类型: like / dislike / none, 派生自 feedback, 便于索引过滤")
        private String feedbackType;

        @Schema(description = "用户反馈详情 (可选)")
        private Feedback feedback;

        @Schema(description = "管线追踪意图 (点踩时自动记录)")
        private String traceIntent;

        @Schema(description = "管线追踪阶段列表 JSON (点踩时自动记录, 含召回文档/耗时/状态)")
        private String traceStagesJson;
    }

    @Data
    @Schema(name = "ChatLogFeedback")
    public static class Feedback {
        @Schema(description = "like / dislike")
        private String type;

        @Schema(description = "用户评论 (200 字内)")
        private String comment;

        @Schema(description = "反馈提交时间")
        private Instant timestamp;
    }
}

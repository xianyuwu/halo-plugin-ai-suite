package run.halo.ai.suite.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.extension.AgentTaskRecord;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
@RequiredArgsConstructor
public class AgentTaskRecordService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ReactiveExtensionClient client;

    public Mono<List<Map<String, Object>>> listRecords() {
        return client.listAll(AgentTaskRecord.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.desc("spec.createdAt")))
            .map(this::toSummary)
            .collectList();
    }

    public Mono<Map<String, Object>> getRecord(String taskId) {
        return client.fetch(AgentTaskRecord.class, taskId)
            .map(this::toDetail);
    }

    public Mono<Void> deleteRecord(String taskId) {
        return client.fetch(AgentTaskRecord.class, taskId)
            .flatMap(client::delete)
            .then();
    }

    public Mono<Map<String, Object>> createRunningTask(String taskType, Map<String, Object> input) {
        return Mono.fromCallable(() -> {
            Instant now = Instant.now();
            String taskId = "agent-" + UUID.randomUUID();
            AgentTaskRecord record = new AgentTaskRecord();
            Metadata md = new Metadata();
            md.setName(taskId);
            record.setMetadata(md);

            AgentTaskRecord.Spec spec = new AgentTaskRecord.Spec();
            spec.setTaskId(taskId);
            spec.setTaskType(taskType);
            spec.setStatus("running");
            spec.setSummary("内容缺口分析运行中");
            spec.setError("");
            spec.setProgress(5);
            spec.setCurrentStep("创建任务");
            spec.setCreatedAt(now);
            spec.setCompletedAt(null);
            spec.setDurationMs(0L);
            spec.setInputJson(toJson(input));
            spec.setMetricsJson("{}");
            spec.setStepsJson(toJson(Map.of("steps", initialSteps())));
            spec.setReportJson(toJson(runningReport(input, initialSteps())));
            record.setSpec(spec);

            AgentTaskRecord saved = client.create(record).block();
            return toDetail(saved);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> completeTask(String taskId, Map<String, Object> result) {
        return updateTask(taskId, result, Boolean.TRUE.equals(result.get("success")) ? "success" : "failed");
    }

    public Mono<Void> updateProgress(String taskId, int progress, String currentStep,
                                     List<Map<String, Object>> steps) {
        return client.fetch(AgentTaskRecord.class, taskId)
            .flatMap(record -> Mono.fromCallable(() -> {
                AgentTaskRecord.Spec spec = record.getSpec();
                if (spec == null) {
                    spec = new AgentTaskRecord.Spec();
                    record.setSpec(spec);
                }
                int safeProgress = Math.min(99, Math.max(0, progress));
                spec.setStatus("running");
                spec.setProgress(safeProgress);
                spec.setCurrentStep(currentStep != null ? currentStep : "");
                spec.setSummary(currentStep != null && !currentStep.isBlank()
                    ? currentStep : "内容缺口分析运行中");
                spec.setStepsJson(toJson(Map.of("steps", steps != null ? steps : initialSteps())));
                spec.setReportJson(toJson(runningReport(fromJson(spec.getInputJson()),
                    steps != null ? steps : initialSteps())));
                client.update(record).block();
                return 0;
            }).subscribeOn(Schedulers.boundedElastic()))
            .then();
    }

    public Mono<Void> failTask(String taskId, String error) {
        Map<String, Object> input = Map.of();
        Map<String, Object> report = failedReport(error);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("input", input);
        result.put("report", report);
        result.put("error", error != null ? error : "运营智能体运行失败");
        return updateTask(taskId, result, "failed");
    }

    public Mono<Map<String, Object>> saveResult(String taskType,
                                                Instant startedAt,
                                                Map<String, Object> result) {
        return Mono.fromCallable(() -> {
            Instant completedAt = Instant.now();
            String taskId = "agent-" + UUID.randomUUID();
            AgentTaskRecord record = new AgentTaskRecord();
            Metadata md = new Metadata();
            md.setName(taskId);
            record.setMetadata(md);
            record.setSpec(toSpec(taskId, taskType, startedAt, completedAt, result));
            AgentTaskRecord saved = client.create(record).block();
            return withTask(toDetail(saved), result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> updateTask(String taskId, Map<String, Object> result, String status) {
        return client.fetch(AgentTaskRecord.class, taskId)
            .flatMap(record -> Mono.fromCallable(() -> {
                AgentTaskRecord.Spec spec = record.getSpec();
                if (spec == null) {
                    spec = new AgentTaskRecord.Spec();
                    record.setSpec(spec);
                }
                Instant completedAt = Instant.now();
                Instant startedAt = spec.getCreatedAt() != null ? spec.getCreatedAt() : completedAt;
                spec.setStatus(status);
                spec.setError(stringVal(result.get("error")));
                spec.setProgress("success".equals(status) ? 100 : Math.max(0,
                    spec.getProgress() != null ? spec.getProgress() : 0));
                spec.setCurrentStep("success".equals(status) ? "分析完成" : "分析失败");
                spec.setCompletedAt(completedAt);
                spec.setDurationMs(Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli()));

                @SuppressWarnings("unchecked")
                Map<String, Object> input = result.get("input") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : fromJson(spec.getInputJson());
                @SuppressWarnings("unchecked")
                Map<String, Object> report = result.get("report") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
                @SuppressWarnings("unchecked")
                Map<String, Object> metrics = input.get("metrics") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();

                spec.setInputJson(toJson(input));
                spec.setMetricsJson(toJson(metrics));
                spec.setReportJson(toJson(report));
                spec.setSummary(stringVal(report.get("summary")));
                spec.setStepsJson(toJson(Map.of("steps", report.get("steps") instanceof List<?> steps
                    ? steps : List.of())));
                client.update(record).block();
                return 0;
            }).subscribeOn(Schedulers.boundedElastic()))
            .then();
    }

    private AgentTaskRecord.Spec toSpec(String taskId,
                                        String taskType,
                                        Instant startedAt,
                                        Instant completedAt,
                                        Map<String, Object> result) {
        AgentTaskRecord.Spec spec = new AgentTaskRecord.Spec();
        spec.setTaskId(taskId);
        spec.setTaskType(taskType);
        spec.setStatus(Boolean.TRUE.equals(result.get("success")) ? "success" : "failed");
        spec.setError(stringVal(result.get("error")));
        spec.setCreatedAt(startedAt);
        spec.setCompletedAt(completedAt);
        spec.setDurationMs(Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli()));

        @SuppressWarnings("unchecked")
        Map<String, Object> input = result.get("input") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = result.get("report") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = input.get("metrics") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();

        spec.setInputJson(toJson(input));
        spec.setMetricsJson(toJson(metrics));
        spec.setReportJson(toJson(report));
        spec.setSummary(stringVal(report.get("summary")));
        return spec;
    }

    private Map<String, Object> toSummary(AgentTaskRecord record) {
        AgentTaskRecord.Spec spec = record.getSpec();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", spec != null && spec.getTaskId() != null
            ? spec.getTaskId() : record.getMetadata().getName());
        body.put("taskType", spec != null ? spec.getTaskType() : "");
        body.put("status", spec != null ? spec.getStatus() : "");
        body.put("summary", spec != null ? spec.getSummary() : "");
        body.put("error", spec != null ? spec.getError() : "");
        body.put("progress", spec != null && spec.getProgress() != null ? spec.getProgress() : 0);
        body.put("currentStep", spec != null ? spec.getCurrentStep() : "");
        body.put("createdAt", spec != null && spec.getCreatedAt() != null
            ? spec.getCreatedAt().toString() : "");
        body.put("completedAt", spec != null && spec.getCompletedAt() != null
            ? spec.getCompletedAt().toString() : "");
        body.put("durationMs", spec != null && spec.getDurationMs() != null ? spec.getDurationMs() : 0);
        body.put("metrics", spec != null ? fromJson(spec.getMetricsJson()) : Map.of());
        body.put("steps", spec != null
            ? fromJson(spec.getStepsJson()).getOrDefault("steps", List.of()) : List.of());
        return body;
    }

    private Map<String, Object> toDetail(AgentTaskRecord record) {
        Map<String, Object> body = toSummary(record);
        AgentTaskRecord.Spec spec = record.getSpec();
        body.put("success", "success".equals(body.get("status")));
        body.put("template", spec != null ? spec.getTaskType() : "");
        body.put("input", spec != null ? fromJson(spec.getInputJson()) : Map.of());
        body.put("report", spec != null ? fromJson(spec.getReportJson()) : Map.of());
        return body;
    }

    private List<Map<String, Object>> initialSteps() {
        return List.of(
            step("创建任务", "done", 5, "任务已保存为后台运行记录"),
            step("收集数据", "pending", 25, "读取问答记录和公开文章"),
            step("准备输入", "pending", 40, "压缩样本并计算输入指标"),
            step("调用模型", "pending", 70, "生成内容缺口分析报告"),
            step("解析保存", "pending", 90, "解析 JSON 并写入任务记录"),
            step("完成", "pending", 100, "报告可查看")
        );
    }

    public List<Map<String, Object>> progressSteps(String activeName, int progress) {
        return initialSteps().stream()
            .map(step -> {
                String name = String.valueOf(step.get("name"));
                int pct = step.get("percent") instanceof Number n ? n.intValue() : 0;
                String status;
                if (pct < progress) {
                    status = "done";
                } else if (name.equals(activeName)) {
                    status = "running";
                } else {
                    status = "pending";
                }
                Map<String, Object> copy = new LinkedHashMap<>(step);
                copy.put("status", status);
                return copy;
            })
            .toList();
    }

    private Map<String, Object> step(String name, String status, int percent, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("status", status);
        step.put("percent", percent);
        step.put("detail", detail);
        return step;
    }

    private Map<String, Object> runningReport(Map<String, Object> input, List<Map<String, Object>> steps) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", "内容缺口分析正在后台运行，刷新页面不会中断任务。");
        report.put("steps", steps != null ? steps : initialSteps());
        report.put("contentGaps", List.of());
        report.put("articleUpdates", List.of());
        report.put("nextActions", List.of("任务完成后会自动显示报告", "刷新页面后可在最近运行中继续查看"));
        report.put("limitations", List.of());
        return report;
    }

    private Map<String, Object> failedReport(String error) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", "运营智能体运行失败。");
        report.put("steps", List.of(
            Map.of("name", "后台任务", "status", "failed", "detail", error != null ? error : "未知错误")
        ));
        report.put("contentGaps", List.of());
        report.put("articleUpdates", List.of());
        report.put("nextActions", List.of("检查模型配置后重新运行", "如仍失败，请查看 Halo 日志"));
        report.put("limitations", List.of());
        return report;
    }

    private Map<String, Object> withTask(Map<String, Object> detail, Map<String, Object> result) {
        Map<String, Object> body = new LinkedHashMap<>(result);
        body.put("taskId", detail.get("taskId"));
        body.put("taskType", detail.get("taskType"));
        body.put("status", detail.get("status"));
        body.put("createdAt", detail.get("createdAt"));
        body.put("completedAt", detail.get("completedAt"));
        body.put("durationMs", detail.get("durationMs"));
        return body;
    }

    private String stringVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value != null ? value : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}

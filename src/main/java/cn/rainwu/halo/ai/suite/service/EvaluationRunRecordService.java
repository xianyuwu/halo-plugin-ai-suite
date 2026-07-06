package cn.rainwu.halo.ai.suite.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import cn.rainwu.halo.ai.suite.extension.EvaluationRunRecord;
import cn.rainwu.halo.ai.suite.service.EvaluationService.EvaluationCaseResult;
import cn.rainwu.halo.ai.suite.service.EvaluationService.EvaluationReport;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationRunRecordService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ReactiveExtensionClient client;

    public Mono<List<RunRecordDto>> listRecords() {
        return client.listAll(EvaluationRunRecord.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.desc("spec.startedAt")))
            .map(this::toDto)
            .collectList();
    }

    public Mono<EvaluationReport> getReport(String runId) {
        return client.fetch(EvaluationRunRecord.class, runId)
            .map(this::toReport);
    }

    public Mono<EvaluationReport> saveReport(EvaluationReport report, String datasetId) {
        return Mono.fromCallable(() -> {
            EvaluationRunRecord record = new EvaluationRunRecord();
            Metadata md = new Metadata();
            md.setName(report.runId());
            record.setMetadata(md);
            record.setSpec(toSpec(report, datasetId));
            EvaluationRunRecord saved = client.create(record).block();
            return toReport(saved);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteRecord(String runId) {
        return client.fetch(EvaluationRunRecord.class, runId)
            .flatMap(client::delete)
            .then();
    }

    private EvaluationRunRecord.Spec toSpec(EvaluationReport report, String datasetId) {
        EvaluationRunRecord.Spec spec = new EvaluationRunRecord.Spec();
        spec.setRunId(report.runId());
        spec.setDatasetId(datasetId != null ? datasetId : "");
        spec.setDisplayName(report.name());
        spec.setStartedAt(parseInstant(report.startedAt()));
        spec.setDurationMs(report.durationMs());
        spec.setSummary(toSummary(report.summary()));
        // results 序列化为 JSON 字符串，避免嵌套 record 验证失败
        try {
            String json = objectMapper.writeValueAsString(
                report.results() != null ? report.results() : List.of());
            spec.setResultsJson(json);
        } catch (Exception e) {
            log.warn("[EvaluationRunRecordService] 序列化 results 失败: {}", e.getMessage());
            spec.setResultsJson("[]");
        }
        return spec;
    }

    private EvaluationRunRecord.EvaluationSummary toSummary(
            EvaluationService.EvaluationSummary src) {
        if (src == null) return null;
        EvaluationRunRecord.EvaluationSummary s = new EvaluationRunRecord.EvaluationSummary();
        s.setTotalCases(src.totalCases());
        s.setAvgScore(src.avgScore());
        s.setRelevance(src.relevance());
        s.setCorrectness(src.correctness());
        s.setFaithfulness(src.faithfulness());
        s.setCompleteness(src.completeness());
        s.setCitationAccuracy(src.citationAccuracy());
        s.setRetrievalHitRate(src.retrievalHitRate());
        s.setHallucinationHighRiskRate(src.hallucinationHighRiskRate());
        s.setFailedCases(src.failedCases());
        return s;
    }

    private EvaluationReport toReport(EvaluationRunRecord record) {
        EvaluationRunRecord.Spec spec = record.getSpec();
        String runId = spec != null && spec.getRunId() != null
            ? spec.getRunId() : record.getMetadata().getName();
        String name = spec != null ? spec.getDisplayName() : "";
        String startedAt = spec != null && spec.getStartedAt() != null
            ? spec.getStartedAt().toString() : "";
        long durationMs = spec != null && spec.getDurationMs() != null
            ? spec.getDurationMs() : 0;
        EvaluationService.EvaluationSummary summary = toServiceSummary(
            spec != null ? spec.getSummary() : null);
        List<EvaluationCaseResult> results = deserializeResults(
            spec != null ? spec.getResultsJson() : null);
        return new EvaluationReport(runId, name, startedAt, durationMs, summary, results);
    }

    private EvaluationService.EvaluationSummary toServiceSummary(
            EvaluationRunRecord.EvaluationSummary src) {
        if (src == null) return null;
        return new EvaluationService.EvaluationSummary(
            src.getTotalCases(), src.getAvgScore(), src.getRelevance(),
            src.getCorrectness(), src.getFaithfulness(), src.getCompleteness(),
            src.getCitationAccuracy(), src.getRetrievalHitRate(),
            src.getHallucinationHighRiskRate(), src.getFailedCases()
        );
    }

    private List<EvaluationCaseResult> deserializeResults(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[EvaluationRunRecordService] 反序列化 results 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private RunRecordDto toDto(EvaluationRunRecord record) {
        EvaluationRunRecord.Spec spec = record.getSpec();
        EvaluationService.EvaluationSummary summary = toServiceSummary(
            spec != null ? spec.getSummary() : null);
        return new RunRecordDto(
            spec != null && spec.getRunId() != null
                ? spec.getRunId() : record.getMetadata().getName(),
            spec != null ? spec.getDatasetId() : "",
            spec != null ? spec.getDisplayName() : "",
            spec != null && spec.getStartedAt() != null ? spec.getStartedAt().toString() : "",
            spec != null && spec.getDurationMs() != null ? spec.getDurationMs() : 0,
            summary
        );
    }

    private Instant parseInstant(String value) {
        try {
            return value != null && !value.isBlank() ? Instant.parse(value) : Instant.now();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    public record RunRecordDto(
        String runId,
        String datasetId,
        String name,
        String startedAt,
        long durationMs,
        EvaluationService.EvaluationSummary summary
    ) {}
}

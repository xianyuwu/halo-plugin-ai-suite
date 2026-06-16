package run.halo.ai.suite.endpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.service.EvaluationDatasetService;
import run.halo.ai.suite.service.EvaluationDatasetService.SaveDatasetRequest;
import run.halo.ai.suite.service.EvaluationRunRecordService;
import run.halo.ai.suite.service.EvaluationService;
import run.halo.ai.suite.service.EvaluationService.EvaluationRunRequest;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.Map;

/**
 * 管理端效果评测 API。
 *
 * <p>路由前缀：/apis/console.api.ai-suite.halo.run/v1alpha1/evaluations
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleEvaluationEndpoint implements CustomEndpoint {

    private final EvaluationService evaluationService;
    private final EvaluationDatasetService datasetService;
    private final EvaluationRunRecordService runRecordService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/evaluations/template", this::handleTemplate)
            .GET("/evaluations/datasets", this::handleListDatasets)
            .GET("/evaluations/datasets/{id}", this::handleGetDataset)
            .POST("/evaluations/datasets", this::handleSaveDataset)
            .PUT("/evaluations/datasets/{id}", this::handleUpdateDataset)
            .DELETE("/evaluations/datasets/{id}", this::handleDeleteDataset)
            .GET("/evaluations/runs", this::handleListRuns)
            .GET("/evaluations/runs/{runId}", this::handleGetRun)
            .GET("/evaluations/runs/{runId}/status", this::handleRunStatus)
            .DELETE("/evaluations/runs/{runId}", this::handleDeleteRun)
            .POST("/evaluations/run", this::handleRun)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> handleRun(ServerRequest request) {
        return request.bodyToMono(EvaluationRunRequest.class)
            .flatMap(runRequest -> {
                String runId = evaluationService.submit(runRequest, runRequest.datasetId(), runRecordService);
                return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("runId", runId, "status", "running"));
            })
            .onErrorResume(e -> {
                log.error("[ConsoleEvaluationEndpoint] 提交评测任务失败", e);
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", e.getMessage()));
            });
    }

    private Mono<ServerResponse> handleRunStatus(ServerRequest request) {
        String runId = request.pathVariable("runId");
        EvaluationService.RunStatus status = evaluationService.getStatus(runId);
        if (status == null) {
            return ServerResponse.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "任务不存在: " + runId));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("runId", status.runId());
        body.put("status", status.status());
        body.put("totalCases", status.totalCases());
        body.put("completedCases", status.completedCases().get());
        body.put("currentCase", status.currentCase());
        if (status.error() != null) body.put("error", status.error());
        if (status.report() != null) body.put("report", status.report());
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    private Mono<ServerResponse> handleListRuns(ServerRequest request) {
        return runRecordService.listRecords()
            .flatMap(records -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(records));
    }

    private Mono<ServerResponse> handleGetRun(ServerRequest request) {
        return runRecordService.getReport(request.pathVariable("runId"))
            .flatMap(report -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(report));
    }

    private Mono<ServerResponse> handleDeleteRun(ServerRequest request) {
        return runRecordService.deleteRecord(request.pathVariable("runId"))
            .then(ServerResponse.noContent().build())
            .onErrorResume(e -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    private Mono<ServerResponse> handleTemplate(ServerRequest request) {
        return datasetService.defaultTemplate()
            .flatMap(dataset -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dataset));
    }

    private Mono<ServerResponse> handleListDatasets(ServerRequest request) {
        return datasetService.listDatasets()
            .flatMap(datasets -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(datasets));
    }

    private Mono<ServerResponse> handleGetDataset(ServerRequest request) {
        return datasetService.getDataset(request.pathVariable("id"))
            .flatMap(dataset -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dataset));
    }

    private Mono<ServerResponse> handleSaveDataset(ServerRequest request) {
        return request.bodyToMono(SaveDatasetRequest.class)
            .flatMap(datasetService::saveDataset)
            .flatMap(dataset -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dataset))
            .onErrorResume(e -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    private Mono<ServerResponse> handleUpdateDataset(ServerRequest request) {
        String id = request.pathVariable("id");
        return request.bodyToMono(SaveDatasetRequest.class)
            .map(body -> new SaveDatasetRequest(id, body.name(), body.description(), body.cases()))
            .flatMap(datasetService::saveDataset)
            .flatMap(dataset -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dataset))
            .onErrorResume(e -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    private Mono<ServerResponse> handleDeleteDataset(ServerRequest request) {
        return datasetService.deleteDataset(request.pathVariable("id"))
            .then(ServerResponse.noContent().build())
            .onErrorResume(e -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())));
    }
}

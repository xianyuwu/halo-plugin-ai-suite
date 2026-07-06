package cn.rainwu.halo.ai.suite.endpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import cn.rainwu.halo.ai.suite.agent.AgentTaskRecordService;
import cn.rainwu.halo.ai.suite.agent.ContentGapAgentService;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import java.util.Map;

/**
 * 管理端运营智能体 API.
 */
@Component
@RequiredArgsConstructor
public class ConsoleAgentEndpoint implements CustomEndpoint {

    private final ContentGapAgentService contentGapAgentService;
    private final AgentTaskRecordService taskRecordService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/agent/content-gap/run", this::handleRunContentGap)
            .GET("/agent/tasks", this::handleListTasks)
            .GET("/agent/tasks/{taskId}", this::handleGetTask)
            .DELETE("/agent/tasks/{taskId}", this::handleDeleteTask)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> handleRunContentGap(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .defaultIfEmpty(Map.of())
            .flatMap(body -> {
                int days = intVal(body.get("days"), 30);
                int maxLogs = intVal(body.get("maxLogs"), 80);
                int maxTokens = intVal(body.get("maxTokens"), 5000);
                Map<String, Object> input = Map.of(
                    "days", days,
                    "maxLogs", maxLogs,
                    "maxTokens", maxTokens
                );
                return taskRecordService.createRunningTask("content-gap", input)
                    .doOnSuccess(task -> startContentGapTask(
                        String.valueOf(task.get("taskId")), days, maxLogs, maxTokens));
            })
            .flatMap(task -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(task))
            .onErrorResume(e -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "success", false,
                    "error", "运营智能体运行失败，请查看 Halo 日志或稍后重试。",
                    "technicalError", e.getMessage()
                )));
    }

    private void startContentGapTask(String taskId, int days, int maxLogs, int maxTokens) {
        contentGapAgentService.run(days, maxLogs, maxTokens, (progress, step) ->
                taskRecordService.updateProgress(taskId, progress, step,
                        taskRecordService.progressSteps(step, progress))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe())
            .flatMap(result -> taskRecordService.completeTask(taskId, result))
            .onErrorResume(e -> taskRecordService.failTask(taskId,
                e.getMessage() != null ? e.getMessage() : "运营智能体运行失败"))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    private Mono<ServerResponse> handleListTasks(ServerRequest request) {
        return taskRecordService.listRecords()
            .flatMap(records -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(records));
    }

    private Mono<ServerResponse> handleGetTask(ServerRequest request) {
        return taskRecordService.getRecord(request.pathVariable("taskId"))
            .flatMap(record -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(record))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> handleDeleteTask(ServerRequest request) {
        return taskRecordService.deleteRecord(request.pathVariable("taskId"))
            .then(ServerResponse.noContent().build())
            .onErrorResume(e -> ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    private int intVal(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}

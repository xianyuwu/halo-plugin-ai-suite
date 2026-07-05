package run.halo.ai.suite.endpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.extension.IntentRoute;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.ai.suite.intent.PipelineExecutor;
import run.halo.ai.suite.rag.PipelineTrace;
import run.halo.ai.suite.service.IntentRouteService;
import run.halo.ai.suite.service.IntentRouteGenerationService;
import run.halo.ai.suite.service.IntentRouteService.IntentRouteDto;
import run.halo.ai.suite.service.IntentRouteService.SaveIntentRequest;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端意图路由 API — 增删改查 IntentRoute CRD.
 *
 * <p>路由前缀：/apis/console.api.ai-suite.halo.run/v1alpha1/intent-routes
 *
 * <p>权限：group 前缀 {@code console.api.} 由 Halo 框架自动要求管理员认证（与
 * {@link ConsoleEvaluationEndpoint} 同机制），无需显式 RoleTemplate。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleIntentRouteEndpoint implements CustomEndpoint {

    private final IntentRouteService intentRouteService;
    private final IntentRouteGenerationService generationService;
    private final PipelineExecutor pipelineExecutor;
    private final ReactiveExtensionClient client;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/intent-routes", this::handleList)
            .POST("/intent-routes/generate", this::handleGenerate)
            .POST("/intent-routes/simulate", this::handleSimulate)
            .GET("/intent-routes/{id}", this::handleGet)
            .POST("/intent-routes", this::handleSave)
            .PUT("/intent-routes/{id}", this::handleUpdate)
            .DELETE("/intent-routes/{id}", this::handleDelete)
            // 试跑预览：跑一遍该意图的 pipeline，返回每步 trace（in/out/posts）
            .POST("/intent-routes/{id}/preview", this::handlePreview)
            .build();
    }

    private Mono<ServerResponse> handleGenerate(ServerRequest request) {
        return request.bodyToMono(GenerateRequest.class)
            .flatMap(body -> generationService.generate(body.requirement()))
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON).bodyValue(result))
            .onErrorResume(IllegalArgumentException.class, e -> ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("error", e.getMessage())))
            .onErrorResume(e -> {
                log.warn("[IntentRouteGenerate] 生成失败: {}", e.getMessage(), e);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("error", "AI 生成失败"));
            });
    }

    private Mono<ServerResponse> handleSimulate(ServerRequest request) {
        return request.bodyToMono(SimulateRequest.class)
            .flatMap(body -> {
                if (body.draft() == null) {
                    return Mono.error(new IllegalArgumentException("路由草稿不能为空"));
                }
                intentRouteService.validateDraft(body.draft());
                String query = body.query() != null ? body.query().trim() : "";
                if (query.isEmpty() || query.length() > 1000) {
                    return Mono.error(new IllegalArgumentException("测试问题必填且不能超过 1000 个字符"));
                }
                IntentRoute route = toTemporaryRoute(body.draft());
                PipelineTrace trace = new PipelineTrace(query, "ai-generated-draft");
                return pipelineExecutor.execute(route, query, List.of(), trace)
                    .map(posts -> {
                        Map<String, Object> result = new LinkedHashMap<>(serializeTrace(trace));
                        result.put("matched", matchesDraft(body.draft(), query));
                        result.put("issues", generationService.inspectDraft(body.draft()));
                        result.put("resultCount", posts.size());
                        return result;
                    });
            })
            .flatMap(result -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(result))
            .onErrorResume(IllegalArgumentException.class, e -> ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("error", e.getMessage())))
            .onErrorResume(e -> {
                log.warn("[IntentRouteSimulate] 模拟失败: {}", e.getMessage(), e);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("error", "模拟失败: " + e.getMessage()));
            });
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> handleList(ServerRequest request) {
        return intentRouteService.listIntents()
            .flatMap(routes -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(routes));
    }

    private Mono<ServerResponse> handleGet(ServerRequest request) {
        return intentRouteService.getIntent(request.pathVariable("id"))
            .flatMap(route -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(route))
            .switchIfEmpty(ServerResponse.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "意图不存在")));
    }

    private Mono<ServerResponse> handleSave(ServerRequest request) {
        return request.bodyToMono(SaveIntentRequest.class)
            .flatMap(intentRouteService::saveIntent)
            .flatMap(route -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(route))
            .onErrorResume(IllegalArgumentException.class, e -> ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())))
            .onErrorResume(e -> ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "保存意图失败")));
    }

    private Mono<ServerResponse> handleUpdate(ServerRequest request) {
        String id = request.pathVariable("id");
        return request.bodyToMono(SaveIntentRequest.class)
            .map(body -> overrideId(body, id))
            .flatMap(intentRouteService::saveIntent)
            .flatMap(route -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(route))
            .onErrorResume(IllegalArgumentException.class, e -> ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())))
            .onErrorResume(e -> ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "更新意图失败")));
    }

    private Mono<ServerResponse> handleDelete(ServerRequest request) {
        return intentRouteService.deleteIntent(request.pathVariable("id"))
            .then(ServerResponse.noContent().build())
            .onErrorResume(IllegalArgumentException.class, e -> ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())))
            .onErrorResume(e -> ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", "删除意图失败")));
    }

    /**
     * 试跑预览 — 用示例问题跑一遍该意图配置的 pipeline，返回完整 trace.
     * <p>前端拿 trace.stages 渲染到每个节点下方（in→out 文章数 + 标题列表）.
     * <p>注意：含 LLM 的处理器（TOPIC_MATCH/LLM_TITLE_FILTER）会真实消耗 token，
     * 用 UsageScenario.INTENT_PIPELINE 计费（处理器内硬编码）.
     */
    private Mono<ServerResponse> handlePreview(ServerRequest request) {
        String id = request.pathVariable("id");
        return request.bodyToMono(PreviewRequest.class)
            .flatMap(body -> {
                String query = body.query() != null ? body.query() : "";
                return client.fetch(IntentRoute.class, id)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("意图不存在")))
                    .flatMap(route -> {
                        PipelineTrace trace = new PipelineTrace(query, id);
                        return pipelineExecutor.execute(route, query, List.of(), trace)
                            .thenReturn(trace);
                    })
                    .flatMap(trace -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(serializeTrace(trace)));
            })
            .onErrorResume(IllegalArgumentException.class, e -> ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", e.getMessage())))
            .onErrorResume(e -> {
                log.warn("[IntentRoutePreview] 预览失败: {}", e.getMessage(), e);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("error", "预览失败: " + e.getMessage()));
            });
    }

    /** 把 PipelineTrace 序列化成前端友好的扁平 JSON 结构. */
    private static Map<String, Object> serializeTrace(PipelineTrace trace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", trace.query());
        result.put("intent", trace.intent());
        List<Map<String, Object>> stages = new ArrayList<>();
        for (PipelineTrace.TraceStage s : trace.stages()) {
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("name", s.name());
            stage.put("label", s.label());
            stage.put("status", s.status());
            stage.put("statusLabel", s.statusLabel());
            stage.put("detail", s.detail());
            stage.put("durationMs", s.durationMs());
            stage.put("data", s.data());
            stages.add(stage);
        }
        result.put("stages", stages);
        return result;
    }

    record PreviewRequest(String query) {}
    record GenerateRequest(String requirement) {}
    record SimulateRequest(SaveIntentRequest draft, String query) {}

    private static IntentRoute toTemporaryRoute(SaveIntentRequest draft) {
        IntentRoute route = new IntentRoute();
        Metadata metadata = new Metadata();
        metadata.setName("ai-generated-draft");
        route.setMetadata(metadata);
        IntentRoute.Spec spec = new IntentRoute.Spec();
        spec.setDisplayName(draft.displayName());
        spec.setDescription(draft.description());
        spec.setEnabled(false);
        spec.setPriority(draft.priority());
        spec.setBuiltin(false);
        spec.setTriggerPatterns(draft.triggerPatterns());
        spec.setLlmFallback(draft.llmFallback());
        spec.setLlmFallbackHint(draft.llmFallbackHint());
        spec.setPipeline(draft.pipeline());
        spec.setOutputTemplate(draft.outputTemplate());
        route.setSpec(spec);
        return route;
    }

    private static boolean matchesDraft(SaveIntentRequest draft, String query) {
        if (draft.triggerPatterns() == null) return false;
        for (String value : draft.triggerPatterns()) {
            try {
                if (java.util.regex.Pattern.compile(value, java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(query).find()) return true;
            } catch (RuntimeException ignored) {
                // validateDraft 已负责报告非法正则。
            }
        }
        return false;
    }

    /** PUT 时用路径上的 id 覆盖 body 里的 id，避免不一致. */
    private static SaveIntentRequest overrideId(SaveIntentRequest body, String id) {
        return new SaveIntentRequest(
            id,
            body.displayName(),
            body.description(),
            body.enabled(),
            body.priority(),
            body.triggerPatterns(),
            body.llmFallback(),
            body.llmFallbackHint(),
            body.pipeline(),
            body.outputTemplate()
        );
    }

    // 仅供前端类型参考导出（Java 不暴露，前端通过 DTO record 形状即可）.
    @SuppressWarnings("unused")
    private static List<PipelineStep> unusedPipelineTypeHint() { return List.of(); }
    @SuppressWarnings("unused")
    private static IntentRouteDto unusedDtoTypeHint() { return null; }
}

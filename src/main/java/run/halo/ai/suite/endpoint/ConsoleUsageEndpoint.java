package run.halo.ai.suite.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.config.AIProperties.ModelConfig;
import run.halo.ai.suite.endpoint.UsageLimit.ModelUsageRecord;
import run.halo.ai.suite.endpoint.UsageLimit.UsageLimitsConfig;
import run.halo.ai.suite.endpoint.UsageLimit.VisitorRateLimitConfig;
import run.halo.ai.suite.state.UsageTracker;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台用量统计与限流管理 API.
 *
 * <p>所有路由都在 {@code /apis/console.api.ai-suite.halo.run/v1alpha1/usage/...} 前缀下,
 * 由 Halo 自动要求管理员认证.
 *
 * <p>路由:
 * <ul>
 *   <li>GET  /usage/today       → 当日实时内存读</li>
 *   <li>GET  /usage/stats       → 历史聚合(range=today|7d|30d)</li>
 *   <li>GET  /usage/calls       → 模型 API 调用明细</li>
 *   <li>GET  /usage/failure-diagnostics → 失败原因聚合诊断</li>
 *   <li>GET  /usage/limits      → 读 limits 配置</li>
 *   <li>POST /usage/limits      → 写 limits 配置</li>
 *   <li>GET  /usage/cleanup     → 读用量清理配置</li>
 *   <li>POST /usage/cleanup/hidden → 保存隐藏模型列表</li>
 *   <li>POST /usage/cleanup/merge  → 合并历史模型用量</li>
 *   <li>POST /usage/cleanup/delete → 删除历史模型用量</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleUsageEndpoint implements CustomEndpoint {

    private static final String CONFIG_MAP_NAME = "ai-suite-configmap";
    private static final String LEGACY_CONFIG_MAP_NAME = "ai-assistant-configmap";

    private final AIProperties aiProperties;
    private final UsageTracker usageTracker;
    private final ReactiveExtensionClient extensionClient;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/usage/today", this::handleToday)
            .GET("/usage/stats", this::handleStats)
            .GET("/usage/calls", this::handleCalls)
            .GET("/usage/failure-diagnostics", this::handleFailureDiagnostics)
            .GET("/usage/limits", this::handleGetLimits)
            .POST("/usage/limits", this::handleSaveLimits)
            .GET("/usage/cleanup", this::handleGetCleanup)
            .POST("/usage/cleanup/hidden", this::handleSaveHiddenModels)
            .POST("/usage/cleanup/merge", this::handleMergeModelUsage)
            .POST("/usage/cleanup/delete", this::handleDeleteModelUsage)
            .build();
    }

    private Mono<ServerResponse> handleCalls(ServerRequest request) {
        String model = request.queryParam("model").orElse("").trim();
        String type = request.queryParam("type").orElse("all").trim();
        String scenario = request.queryParam("scenario").orElse("all").trim();
        String status = request.queryParam("status").orElse("all").trim();
        String sort = "asc".equalsIgnoreCase(request.queryParam("sort").orElse("desc").trim())
            ? "asc" : "desc";
        int page = safeInt(request.queryParam("page").orElse("1"), 1);
        int size = safeInt(request.queryParam("size").orElse("20"), 20);

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
        java.time.LocalDate start;
        java.time.LocalDate end;
        try {
            start = java.time.LocalDate.parse(request.queryParam("start")
                .orElse(today.minusDays(6).toString()));
            end = java.time.LocalDate.parse(request.queryParam("end")
                .orElse(today.toString()));
        } catch (Exception e) {
            return ServerResponse.badRequest().bodyValue(
                Map.of("error", "invalid date format, use YYYY-MM-DD"));
        }
        if (end.isBefore(start)) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "end must be >= start"));
        }
        long span = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (span > usageTracker.getCallLogRetentionDays()) {
            return ServerResponse.badRequest().bodyValue(Map.of(
                "error", "call log range max " + usageTracker.getCallLogRetentionDays() + " days"
            ));
        }

        java.time.LocalDate earliest = today.minusDays(usageTracker.getCallLogRetentionDays() - 1L);
        if (start.isBefore(earliest)) {
            start = earliest;
        }

        java.time.LocalDate finalStart = start;
        java.time.LocalDate finalEnd = end;
        return Mono.fromCallable(() -> Map.of(
                "logs", usageTracker.getCallLogs(finalStart, finalEnd, model, type, scenario, status, page, size, sort),
                "types", usageTracker.getCallLogTypes(finalStart, finalEnd, model),
                "scenarios", usageTracker.getCallLogScenarios(finalStart, finalEnd, model)
            ))
            .subscribeOn(Schedulers.boundedElastic())
            .map(resultMap -> {
                var result = (UsageTracker.ModelCallLogPage) resultMap.get("logs");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("start", finalStart.toString());
                body.put("end", finalEnd.toString());
                body.put("retentionDays", usageTracker.getCallLogRetentionDays());
                body.put("sort", sort);
                body.put("types", resultMap.get("types"));
                body.put("scenarios", resultMap.get("scenarios"));
                body.put("total", result.total());
                body.put("page", result.page());
                body.put("size", result.size());
                body.put("items", result.items().stream().map(item -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", item.id());
                    m.put("date", item.date());
                    m.put("time", item.time());
                    m.put("model", item.model());
                    m.put("type", item.type());
                    m.put("scenario", item.scenario());
                    m.put("promptTokens", item.promptTokens());
                    m.put("completionTokens", item.completionTokens());
                    m.put("embeddingTokens", item.embeddingTokens());
                    m.put("totalTokens", item.totalTokens());
                    m.put("failure", item.failure());
                    m.put("durationMs", item.durationMs());
                    m.put("error", item.error());
                    return m;
                }).toList());
                return body;
            })
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    private Mono<ServerResponse> handleFailureDiagnostics(ServerRequest request) {
        String model = request.queryParam("model").orElse("").trim();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
        java.time.LocalDate start;
        java.time.LocalDate end;
        try {
            start = java.time.LocalDate.parse(request.queryParam("start")
                .orElse(today.minusDays(6).toString()));
            end = java.time.LocalDate.parse(request.queryParam("end")
                .orElse(today.toString()));
        } catch (Exception e) {
            return ServerResponse.badRequest().bodyValue(
                Map.of("error", "invalid date format, use YYYY-MM-DD"));
        }
        if (end.isBefore(start)) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "end must be >= start"));
        }
        long span = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (span > usageTracker.getCallLogRetentionDays()) {
            return ServerResponse.badRequest().bodyValue(Map.of(
                "error", "diagnostic range max " + usageTracker.getCallLogRetentionDays() + " days"
            ));
        }
        java.time.LocalDate earliest = today.minusDays(usageTracker.getCallLogRetentionDays() - 1L);
        if (start.isBefore(earliest)) {
            start = earliest;
        }

        java.time.LocalDate finalStart = start;
        java.time.LocalDate finalEnd = end;
        return Mono.fromCallable(() -> buildFailureDiagnostics(finalStart, finalEnd, model))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    private Map<String, Object> buildFailureDiagnostics(java.time.LocalDate start,
                                                        java.time.LocalDate end,
                                                        String model) {
        var failures = usageTracker.getFailedCallLogs(start, end, model);
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        Map<String, Long> scenarioCounts = new LinkedHashMap<>();
        Map<String, Long> diagnosisCounts = new LinkedHashMap<>();
        Map<String, String> diagnosisExamples = new LinkedHashMap<>();

        for (var log : failures) {
            String type = text(log.type()).isBlank() ? "unknown" : text(log.type());
            String scenario = text(log.scenario()).isBlank() ? "unknown" : text(log.scenario());
            String diagnosis = diagnoseFailure(type, log.error());
            typeCounts.merge(type, 1L, Long::sum);
            scenarioCounts.merge(scenario, 1L, Long::sum);
            diagnosisCounts.merge(diagnosis, 1L, Long::sum);
            diagnosisExamples.putIfAbsent(diagnosis, text(log.error()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start", start.toString());
        body.put("end", end.toString());
        body.put("total", failures.size());
        body.put("byType", bucketList(typeCounts, this::typeLabel));
        body.put("byScenario", bucketList(scenarioCounts, this::scenarioLabel));
        body.put("byDiagnosis", diagnosisBucketList(diagnosisCounts, diagnosisExamples));
        body.put("recent", failures.stream()
            .sorted((a, b) -> text(b.time()).compareTo(text(a.time())))
            .limit(8)
            .map(log -> {
                Map<String, Object> item = new LinkedHashMap<>();
                String type = text(log.type()).isBlank() ? "unknown" : text(log.type());
                String scenario = text(log.scenario()).isBlank() ? "unknown" : text(log.scenario());
                String diagnosis = diagnoseFailure(type, log.error());
                item.put("time", log.time());
                item.put("model", log.model());
                item.put("type", type);
                item.put("typeLabel", typeLabel(type));
                item.put("scenario", scenario);
                item.put("scenarioLabel", scenarioLabel(scenario));
                item.put("diagnosis", diagnosis);
                item.put("diagnosisLabel", diagnosisLabel(diagnosis));
                item.put("suggestion", diagnosisSuggestion(diagnosis));
                item.put("error", text(log.error()));
                return item;
            })
            .toList());
        return body;
    }

    private List<Map<String, Object>> bucketList(Map<String, Long> counts,
                                                 java.util.function.Function<String, String> labeler) {
        return counts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .map(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", entry.getKey());
                item.put("label", labeler.apply(entry.getKey()));
                item.put("count", entry.getValue());
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> diagnosisBucketList(Map<String, Long> counts,
                                                          Map<String, String> examples) {
        return counts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .map(entry -> {
                String key = entry.getKey();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", key);
                item.put("label", diagnosisLabel(key));
                item.put("count", entry.getValue());
                item.put("suggestion", diagnosisSuggestion(key));
                item.put("example", examples.getOrDefault(key, ""));
                return item;
            })
            .toList();
    }

    private String diagnoseFailure(String type, String error) {
        String msg = text(error).toLowerCase(java.util.Locale.ROOT);
        String normalizedType = text(type).toLowerCase(java.util.Locale.ROOT);
        if (msg.contains("dimension") || msg.contains("维度")) return "embedding_dimensions";
        if ("embed".equals(normalizedType) || msg.contains("embedding")) return "embedding_model";
        if ("rerank".equals(normalizedType) || msg.contains("rerank") || msg.contains("reranking")) return "rerank_model";
        if (msg.contains("response_format") || msg.contains("json_object")) return "response_format";
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("超时")) return "timeout";
        if (msg.contains("401") || msg.contains("unauthorized") || msg.contains("api key") || msg.contains("apikey")) return "auth";
        if (msg.contains("403") || msg.contains("forbidden")) return "permission";
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many")) return "rate_limit";
        if (msg.contains("model") && (msg.contains("not found") || msg.contains("not exist") || msg.contains("不存在"))) return "model_unavailable";
        if (msg.contains("aimodelservice") || msg.contains("ai foundation")) return "ai_foundation_service";
        if (msg.contains("not mono") || msg.contains("not flux") || msg.contains("sdk")) return "sdk_compatibility";
        return "unknown";
    }

    private String diagnosisLabel(String key) {
        return switch (key) {
            case "embedding_dimensions" -> "Embedding 维度不匹配";
            case "embedding_model" -> "Embedding 模型配置异常";
            case "rerank_model" -> "Rerank 模型配置异常";
            case "response_format" -> "JSON 输出格式不兼容";
            case "timeout" -> "模型调用超时";
            case "auth" -> "鉴权失败";
            case "permission" -> "权限或模型访问受限";
            case "rate_limit" -> "供应商限流";
            case "model_unavailable" -> "模型不可用或名称错误";
            case "ai_foundation_service" -> "AI Foundation 服务不可用";
            case "sdk_compatibility" -> "AI Foundation SDK 兼容性异常";
            default -> "未归类错误";
        };
    }

    private String diagnosisSuggestion(String key) {
        return switch (key) {
            case "embedding_dimensions" -> "检查模型配置里的嵌入维度是否与 AI Foundation 当前 Embedding 模型一致；必要时清空并重建知识库索引。";
            case "embedding_model" -> "确认 AI Foundation 已配置可用的 Embedding 模型，且 AI Suite 模型配置中没有误选语言模型。";
            case "rerank_model" -> "确认 AI Foundation 已配置 Rerank 类型模型；如果供应商不支持 Rerank，先关闭重排序增强。";
            case "response_format" -> "当前模型可能不支持 response_format/json_object；可换支持 JSON mode 的语言模型，或后续改为 prompt 约束解析。";
            case "timeout" -> "检查供应商响应速度、网络连通性和批量大小；索引/关键词提取类任务可降低批量或 token 上限。";
            case "auth" -> "检查 AI Foundation 中该供应商的 API Key、Base URL 和模型权限。";
            case "permission" -> "检查供应商账号是否有该模型访问权限，或模型是否需要单独开通。";
            case "rate_limit" -> "供应商侧限流，建议降低并发、缩小批量，或换更高配额的模型凭据。";
            case "model_unavailable" -> "确认 AI Foundation 下拉选择的模型仍存在、已启用，并且名称没有被供应商下线。";
            case "ai_foundation_service" -> "确认官方 AI Foundation 插件已安装、启用，并已完成默认模型配置。";
            case "sdk_compatibility" -> "AI Foundation 插件版本可能与当前适配代码不一致，需要检查官方插件 API 变更。";
            default -> "打开调用明细查看原始错误；如果集中在某个场景，可按场景进一步排查模型能力与参数。";
        };
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "chat" -> "Chat";
            case "embed" -> "Embedding";
            case "rerank" -> "Rerank";
            default -> type == null || type.isBlank() ? "未知" : type;
        };
    }

    private String scenarioLabel(String scenario) {
        return switch (scenario) {
            case "model_test" -> "模型连通性测试";
            case "visitor_qa" -> "访客问答";
            case "hot_articles" -> "热门文章推荐";
            case "search_answer" -> "搜索综合回答";
            case "search_query_rewrite" -> "搜索查询改写";
            case "search_hyde" -> "HyDE 检索生成";
            case "search_cross_language" -> "跨语言检索翻译";
            case "search_embedding" -> "搜索向量化";
            case "search_rerank" -> "搜索重排序";
            case "index_embedding" -> "文章索引向量化";
            case "keyword_extract" -> "生成关键词";
            case "mindmap_generate" -> "AI 脑图生成";
            case "summary_generate" -> "AI 摘要生成";
            case "writing_assist" -> "写作辅助";
            case "evaluation_answer" -> "效果评测 · 生成回答";
            case "evaluation_judge" -> "效果评测 · AI 评分";
            case "agent_content_gap" -> "运营智能体 · 内容缺口分析";
            case "intent_detect" -> "意图识别";
            case "intent_pipeline" -> "意图路由";
            default -> scenario == null || scenario.isBlank() ? "未标记" : scenario;
        };
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-suite.halo.run", "v1alpha1");
    }

    /** GET /usage/today — 实时内存读, 便宜 */
    private Mono<ServerResponse> handleToday(ServerRequest request) {
        return Mono.fromCallable(usageTracker::getTodayUsage)
            .subscribeOn(Schedulers.boundedElastic())
            .map(records -> Map.of(
                "date", records.isEmpty() ? java.time.LocalDate.now().toString() : records.get(0).date(),
                "models", records.stream().map(rec -> {
                    var s = rec.usage();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("model", rec.model());
                    m.put("promptTokens", s.promptTokens());
                    m.put("completionTokens", s.completionTokens());
                    m.put("calls", s.calls());
                    m.put("failures", s.failures());
                    m.put("embeddingTokens", s.embeddingTokens());
                    return m;
                }).toList()
            ))
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    /**
     * GET /usage/stats?range=today|7d|30d&model=xxx
     * 返回按 date 降序的每日聚合, 同时附当前 limits 配置.
     */
    private Mono<ServerResponse> handleStats(ServerRequest request) {
        String range = request.queryParam("range").orElse("today");
        String modelFilter = request.queryParam("model").orElse("");
        java.util.Optional<String> startParam = request.queryParam("start");
        java.util.Optional<String> endParam = request.queryParam("end");

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
        java.time.LocalDate start;
        java.time.LocalDate end;

        if (startParam.isPresent() && endParam.isPresent()) {
            try {
                start = java.time.LocalDate.parse(startParam.get());
                end = java.time.LocalDate.parse(endParam.get());
            } catch (Exception e) {
                return ServerResponse.badRequest().bodyValue(
                    Map.of("error", "invalid date format, use YYYY-MM-DD"));
            }
            if (end.isBefore(start)) {
                return ServerResponse.badRequest().bodyValue(
                    Map.of("error", "end must be >= start"));
            }
            long span = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
            if (span > 30) {
                return ServerResponse.badRequest().bodyValue(
                    Map.of("error", "range max 30 days (got " + span + ")"));
            }
        } else {
            int days = switch (range) {
                case "7d" -> 7;
                case "30d" -> 30;
                default -> 1;
            };
            end = today;
            start = today.minusDays(days - 1);
        }

        return Mono.fromCallable(() -> buildStats(start, end, modelFilter))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body))
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("error", e.getMessage())));
    }

    private Map<String, Object> buildStats(java.time.LocalDate start,
                                          java.time.LocalDate end,
                                          String modelFilter) {
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        List<Map<String, Object>> dailyList = new java.util.ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
        long totalCalls = 0;
        long totalPrompt = 0;
        long totalCompletion = 0;
        long totalFailures = 0;
        long totalEmbedding = 0;
        int modelCount = 0;
        // 跨天并集 — 累计所有出现过的模型名(去重保序),给前端做"历史发现"
        java.util.LinkedHashSet<String> modelsInRange = new java.util.LinkedHashSet<>();

        for (int i = 0; i < days; i++) {
            java.time.LocalDate date = start.plusDays(i);
            String dateStr = date.toString();
            List<ModelUsageRecord> records = usageTracker.getUsageForDate(dateStr);
            // 不再 skip 空日期 — 7d/30d 范围要画完整 days 根柱, 0 数据用最小高度占位

            long dayPrompt = 0, dayCompletion = 0, dayCalls = 0, dayFailures = 0, dayEmbedding = 0;
            java.util.Set<String> dayModels = new java.util.HashSet<>();
            // byModel: model -> [prompt, completion, embedding, calls, failures]
            Map<String, long[]> byModel = new LinkedHashMap<>();
            for (ModelUsageRecord rec : records) {
                if (!modelFilter.isEmpty() && !rec.model().equals(modelFilter)) continue;
                dayPrompt += rec.usage().promptTokens();
                dayCompletion += rec.usage().completionTokens();
                dayCalls += rec.usage().calls();
                dayFailures += rec.usage().failures();
                dayEmbedding += rec.usage().embeddingTokens();
                dayModels.add(rec.model());
                modelsInRange.add(rec.model());

                // per-model 累加
                long[] arr = byModel.computeIfAbsent(rec.model(), k -> new long[5]);
                arr[0] += rec.usage().promptTokens();
                arr[1] += rec.usage().completionTokens();
                arr[2] += rec.usage().embeddingTokens();
                arr[3] += rec.usage().calls();
                arr[4] += rec.usage().failures();
            }
            // 转换 byModel 为 JSON 友好结构
            Map<String, Object> byModelJson = new LinkedHashMap<>();
            byModel.forEach((model, arr) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("p", arr[0]);
                m.put("c", arr[1]);
                m.put("e", arr[2]);
                m.put("calls", arr[3]);
                m.put("failures", arr[4]);
                byModelJson.put(model, m);
            });
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", dateStr);
            day.put("promptTokens", dayPrompt);
            day.put("completionTokens", dayCompletion);
            day.put("calls", dayCalls);
            day.put("failures", dayFailures);
            day.put("embeddingTokens", dayEmbedding);
            day.put("modelCount", dayModels.size());
            day.put("byModel", byModelJson);
            dailyList.add(day);

            totalPrompt += dayPrompt;
            totalCompletion += dayCompletion;
            totalCalls += dayCalls;
            totalFailures += dayFailures;
            totalEmbedding += dayEmbedding;
            if (!dayModels.isEmpty()) modelCount = Math.max(modelCount, dayModels.size());
        }

        // 昨日对比基线 — 永远取 yesterday 的全集 (忽略 modelFilter, 给用户全量参照)
        long yCalls = 0, yPrompt = 0, yCompletion = 0, yFailures = 0, yEmbedding = 0;
        List<ModelUsageRecord> yRecords = usageTracker.getUsageForDate(today.minusDays(1).toString());
        for (ModelUsageRecord rec : yRecords) {
            yPrompt += rec.usage().promptTokens();
            yCompletion += rec.usage().completionTokens();
            yCalls += rec.usage().calls();
            yFailures += rec.usage().failures();
            yEmbedding += rec.usage().embeddingTokens();
        }
        Map<String, Object> yest = new LinkedHashMap<>();
        yest.put("calls", yCalls);
        yest.put("promptTokens", yPrompt);
        yest.put("completionTokens", yCompletion);
        yest.put("embeddingTokens", yEmbedding);
        yest.put("failures", yFailures);
        yest.put("failureRate", yCalls == 0 ? 0.0
            : Math.round(yFailures * 10000.0 / yCalls) / 100.0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("range", days == 1 ? "today" : (days == 7 ? "7d" : "30d"));
        body.put("start", start.toString());
        body.put("end", end.toString());
        body.put("days", days);
        body.put("daily", dailyList);
        body.put("totals", Map.of(
            "calls", totalCalls,
            "promptTokens", totalPrompt,
            "completionTokens", totalCompletion,
            "embeddingTokens", totalEmbedding,
            "failures", totalFailures,
            "modelCount", modelCount,
            "failureRate", totalCalls == 0 ? 0.0
                : Math.round(totalFailures * 10000.0 / totalCalls) / 100.0
        ));
        body.put("yesterday", yest);
        body.put("modelsInRange", new java.util.ArrayList<>(modelsInRange));
        return body;
    }

    private int safeInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** GET /usage/limits — 读当前配置 + 当前 5 个模型名 + 启用状态 (前端用来展示限流模型列表 + 画多折线图) */
    private Mono<ServerResponse> handleGetLimits(ServerRequest request) {
        return aiProperties.getUsageLimitsConfig()
            .defaultIfEmpty(UsageLimitsConfig.empty())
            .zipWith(aiProperties.getModelConfig())
            .zipWith(aiProperties.getVisitorRateLimitConfig().defaultIfEmpty(VisitorRateLimitConfig.empty()))
            .zipWith(aiProperties.getWritingConfig().defaultIfEmpty(new AIProperties.WritingConfig()))
            .map(tuple -> {
                // 解 4 层 zip: ((cfg, modelCfg), visitor), writing
                ModelConfig mc = tuple.getT1().getT1().getT2();
                AIProperties.WritingConfig wc = tuple.getT2();
                Map<String, Object> m = limitsToMap(tuple.getT1().getT1().getT1(), tuple.getT1().getT2());
                m.put("chatModel", mc != null ? mc.getEffectiveChatModel() : null);
                m.put("embeddingModel", mc != null ? mc.getEffectiveEmbeddingModel() : null);
                m.put("rerankEnabled", mc != null && mc.isRerankEnabled());
                m.put("rerankModel", mc != null ? mc.getEffectiveRerankModel() : null);
                m.put("queryRewriteEnabled", mc != null && mc.isQueryRewriteEnabled());
                m.put("queryRewriteModel", mc != null ? mc.getEffectiveQueryRewriteModel() : null);
                m.put("writingModel", wc != null ? wc.getWritingModel() : "");
                return m;
            })
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body));
    }

    private Map<String, Object> limitsToMap(UsageLimitsConfig cfg,
                                            VisitorRateLimitConfig visitorCfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled());
        m.put("chatModelLimits", cfg.chatModelLimits());
        // 访客限流 (optional, 老配置没有)
        Map<String, Object> v = new LinkedHashMap<>();
        if (visitorCfg != null) {
            v.put("enabled", visitorCfg.enabled());
            v.put("dailyLimit", visitorCfg.dailyLimit());
            v.put("hourlyLimit", visitorCfg.hourlyLimit());
            v.put("whitelist", visitorCfg.whitelist() != null ? visitorCfg.whitelist() : List.of());
        } else {
            v.put("enabled", false);
            v.put("dailyLimit", 0);
            v.put("hourlyLimit", 0);
            v.put("whitelist", List.of());
        }
        m.put("visitor", v);
        return m;
    }

    /** POST /usage/limits — 写 limits 到 ConfigMap(只更新 usageLimits 字段) */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> handleSaveLimits(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("enabled", body.getOrDefault("enabled", false));

                // 标准化: 前端可能发 chatModelLimits (Map) 或 chatModelList (Array)
                Map<String, Long> chatModelLimits = new LinkedHashMap<>();
                Object direct = body.get("chatModelLimits");
                if (direct instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> {
                        try {
                            long lim = Long.parseLong(String.valueOf(v));
                            if (String.valueOf(k).length() > 0) {
                                chatModelLimits.put(String.valueOf(k), lim);
                            }
                        } catch (NumberFormatException ignored) {}
                    });
                } else {
                    // 兼容老格式: 数组 [{model, limit}, ...]
                    Object list = body.get("chatModelList");
                    if (list == null) list = body.get("perModelList");
                    if (list instanceof List<?> arr) {
                        for (Object item : arr) {
                            if (item instanceof Map<?, ?> entry) {
                                Object m = entry.get("model");
                                Object l = entry.get("limit");
                                if (m != null && l != null) {
                                    try {
                                        long lim = Long.parseLong(String.valueOf(l));
                                        chatModelLimits.put(String.valueOf(m), lim);
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                }
                normalized.put("chatModelLimits", chatModelLimits);

                // 标准化 visitor 子对象
                Object visitorObj = body.get("visitor");
                if (visitorObj instanceof Map<?, ?> vMap) {
                    Map<String, Object> vNorm = new LinkedHashMap<>();
                    Object enabledVal = vMap.get("enabled");
                    vNorm.put("enabled", Boolean.TRUE.equals(enabledVal) ||
                        (enabledVal instanceof String s && s.equalsIgnoreCase("true")));
                    vNorm.put("dailyLimit", parseIntSafe(vMap.get("dailyLimit")));
                    vNorm.put("hourlyLimit", parseIntSafe(vMap.get("hourlyLimit")));
                    List<String> wl = new java.util.ArrayList<>();
                    Object wlObj = vMap.get("whitelist");
                    if (wlObj instanceof List<?> wlList) {
                        for (Object item : wlList) {
                            String s = String.valueOf(item).trim();
                            if (!s.isEmpty()) wl.add(s);
                        }
                    }
                    vNorm.put("whitelist", wl);
                    normalized.put("visitor", vNorm);
                }

                String json;
                try {
                    json = objectMapper.writeValueAsString(normalized);
                } catch (Exception e) {
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "JSON 序列化失败: " + e.getMessage()));
                }
                return persistUsageLimits(json)
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(ServerResponse.ok().bodyValue(Map.of("saved", true)));
            })
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("saved", false, "error", e.getMessage())));
    }

    private int parseIntSafe(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(String.valueOf(o)); }
        catch (NumberFormatException e) { return 0; }
    }

    private Mono<ServerResponse> handleGetCleanup(ServerRequest request) {
        return readUsageCleanup()
            .flatMap(body -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body));
    }

    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> handleSaveHiddenModels(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                List<String> hiddenModels = normalizeStringList(body.get("hiddenModels"));
                Map<String, Object> cleanup = new LinkedHashMap<>();
                cleanup.put("hiddenModels", hiddenModels);
                return persistUsageCleanup(cleanup)
                    .then(ServerResponse.ok().bodyValue(Map.of("saved", true)));
            })
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("saved", false, "error", e.getMessage())));
    }

    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> handleMergeModelUsage(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String sourceModel = text(body.get("sourceModel"));
                String targetModel = text(body.get("targetModel"));
                java.time.LocalDate start = parseDate(text(body.get("start")));
                java.time.LocalDate end = parseDate(text(body.get("end")));
                if (sourceModel.isBlank() || targetModel.isBlank()) {
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "sourceModel and targetModel are required"));
                }
                if (end.isBefore(start)) {
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "end must be >= start"));
                }
                return Mono.fromCallable(() -> usageTracker.mergeModelUsage(start, end, sourceModel, targetModel))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(result -> ServerResponse.ok().bodyValue(Map.of(
                        "merged", true,
                        "changedDays", result.changedDays(),
                        "affectedCalls", result.affectedCalls(),
                        "affectedLogs", result.affectedLogs()
                    )));
            })
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("merged", false, "error", e.getMessage())));
    }

    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> handleDeleteModelUsage(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                String model = text(body.get("model"));
                java.time.LocalDate start = parseDate(text(body.get("start")));
                java.time.LocalDate end = parseDate(text(body.get("end")));
                if (model.isBlank()) {
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "model is required"));
                }
                if (end.isBefore(start)) {
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "end must be >= start"));
                }
                return Mono.fromCallable(() -> usageTracker.deleteModelUsage(start, end, model))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(result -> ServerResponse.ok().bodyValue(Map.of(
                        "deleted", true,
                        "changedDays", result.changedDays(),
                        "affectedCalls", result.affectedCalls(),
                        "affectedLogs", result.affectedLogs()
                    )));
            })
            .onErrorResume(e -> ServerResponse.status(500)
                .bodyValue(Map.of("deleted", false, "error", e.getMessage())));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private java.time.LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return java.time.LocalDate.now(java.time.ZoneId.systemDefault());
        }
        return java.time.LocalDate.parse(value);
    }

    private List<String> normalizeStringList(Object value) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = text(item);
                if (!text.isBlank()) result.add(text);
            }
        }
        return new java.util.ArrayList<>(result);
    }

    private Mono<Map<String, Object>> readUsageCleanup() {
        return extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .switchIfEmpty(extensionClient.fetch(ConfigMap.class, LEGACY_CONFIG_MAP_NAME))
            .map(cm -> {
                Map<String, Object> body = new LinkedHashMap<>();
                List<String> hiddenModels = List.of();
                String json = cm.getData() == null ? "" : cm.getData().getOrDefault("usageCleanup", "");
                if (json != null && !json.isBlank()) {
                    try {
                        var node = objectMapper.readTree(json);
                        if (node.path("hiddenModels").isArray()) {
                            java.util.ArrayList<String> list = new java.util.ArrayList<>();
                            node.path("hiddenModels").forEach(item -> {
                                String model = item.asText("");
                                if (!model.isBlank()) list.add(model);
                            });
                            hiddenModels = list;
                        }
                    } catch (Exception ignored) {
                    }
                }
                body.put("hiddenModels", hiddenModels);
                return body;
            })
            .defaultIfEmpty(Map.of("hiddenModels", List.of()));
    }

    private Mono<Void> persistUsageCleanup(Map<String, Object> cleanup) {
        String json;
        try {
            json = objectMapper.writeValueAsString(cleanup);
        } catch (Exception e) {
            return Mono.error(e);
        }
        return updateConfigMapData("usageCleanup", json);
    }

    /** 只更新 usageLimits 字段, 保留 ConfigMap 其他字段 */
    private Mono<Void> persistUsageLimits(String json) {
        return updateConfigMapData("usageLimits", json);
    }

    /** 更新指定 ConfigMap data 字段, 保留其他字段 */
    private Mono<Void> updateConfigMapData(String key, String value) {
        return extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(cm -> {
                var data = cm.getData();
                if (data == null) data = new LinkedHashMap<>();
                data.put(key, value);
                cm.setData(data);
                return extensionClient.update(cm);
            })
            .switchIfEmpty(Mono.defer(() -> {
                ConfigMap cm = new ConfigMap();
                Metadata md = new Metadata();
                md.setName(CONFIG_MAP_NAME);
                cm.setMetadata(md);
                return extensionClient.fetch(ConfigMap.class, LEGACY_CONFIG_MAP_NAME)
                    .map(legacy -> {
                        Map<String, String> data = new LinkedHashMap<>();
                        if (legacy.getData() != null) data.putAll(legacy.getData());
                        data.put(key, value);
                        return data;
                    })
                    .defaultIfEmpty(new LinkedHashMap<>(Map.of(key, value)))
                    .flatMap(data -> {
                        cm.setData(data);
                        return extensionClient.create(cm);
                    });
            }))
            .then();
    }
}

package run.halo.ai.assistant.endpoint;

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
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.config.AIProperties.ModelConfig;
import run.halo.ai.assistant.endpoint.UsageLimit.ModelUsageRecord;
import run.halo.ai.assistant.endpoint.UsageLimit.UsageLimitsConfig;
import run.halo.ai.assistant.endpoint.UsageLimit.VisitorRateLimitConfig;
import run.halo.ai.assistant.state.UsageTracker;
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
 * <p>所有路由都在 {@code /apis/console.api.ai-assistant.halo.run/v1alpha1/usage/...} 前缀下,
 * 由 Halo 自动要求管理员认证.
 *
 * <p>路由:
 * <ul>
 *   <li>GET  /usage/today       → 当日实时内存读</li>
 *   <li>GET  /usage/stats       → 历史聚合(range=today|7d|30d)</li>
 *   <li>GET  /usage/limits      → 读 limits 配置</li>
 *   <li>POST /usage/limits      → 写 limits 配置</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleUsageEndpoint implements CustomEndpoint {

    private final AIProperties aiProperties;
    private final UsageTracker usageTracker;
    private final ReactiveExtensionClient extensionClient;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/usage/today", this::handleToday)
            .GET("/usage/stats", this::handleStats)
            .GET("/usage/limits", this::handleGetLimits)
            .POST("/usage/limits", this::handleSaveLimits)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.ai-assistant.halo.run", "v1alpha1");
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
                m.put("chatModel", mc != null ? mc.getChatModel() : null);
                m.put("embeddingModel", mc != null ? mc.getEmbeddingModel() : null);
                m.put("rerankEnabled", mc != null && mc.isRerankEnabled());
                m.put("rerankModel", mc != null ? mc.getRerankModel() : null);
                m.put("queryRewriteEnabled", mc != null && mc.isQueryRewriteEnabled());
                m.put("queryRewriteModel", mc != null ? mc.getQueryRewriteModel() : null);
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

    /** 只更新 usageLimits 字段, 保留 ConfigMap 其他字段 */
    private Mono<Void> persistUsageLimits(String json) {
        return extensionClient.fetch(ConfigMap.class, "ai-assistant-configmap")
            .flatMap(cm -> {
                var data = cm.getData();
                if (data == null) data = new LinkedHashMap<>();
                data.put("usageLimits", json);
                cm.setData(data);
                return extensionClient.update(cm);
            })
            .switchIfEmpty(Mono.defer(() -> {
                ConfigMap cm = new ConfigMap();
                Metadata md = new Metadata();
                md.setName("ai-assistant-configmap");
                cm.setMetadata(md);
                Map<String, String> data = new LinkedHashMap<>();
                data.put("usageLimits", json);
                cm.setData(data);
                return extensionClient.create(cm);
            }))
            .then();
    }
}

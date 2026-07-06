package cn.rainwu.halo.ai.suite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import cn.rainwu.halo.ai.suite.rag.RAGPipeline;
import cn.rainwu.halo.ai.suite.rag.RetrievedDocument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.scheduler.Schedulers;

/**
 * 效果评测服务。
 *
 * <p>前端传入评测用例，服务依次执行当前 RAG 问答链路，
 * 再用 AI Foundation 语言模型作为裁判输出结构化分数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatService chatService;
    private final RAGPipeline ragPipeline;
    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    /** 运行中的任务状态 */
    private final ConcurrentHashMap<String, RunStatus> runStatusMap = new ConcurrentHashMap<>();

    public record RunStatus(
        String runId,
        String status,       // running / completed / failed
        int totalCases,
        AtomicInteger completedCases,
        String currentCase,
        String error,
        EvaluationReport report
    ) {}

    /** 提交评测任务，立即返回 runId，后台执行 */
    public String submit(EvaluationRunRequest request, String datasetId,
                         EvaluationRunRecordService runRecordService) {
        List<EvaluationCase> cases = request.cases() != null ? request.cases() : List.of();
        String runId = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        RunStatus status = new RunStatus(
            runId, "running", cases.size(), new AtomicInteger(0), "", null, null);
        runStatusMap.put(runId, status);

        Schedulers.boundedElastic().schedule(() -> {
            try {
                EvaluationReport report = runSync(request, runId, status);
                runRecordService.saveReport(report, datasetId).block();
                runStatusMap.put(runId, new RunStatus(runId, "completed", cases.size(),
                    new AtomicInteger(cases.size()), "", null, report));
            } catch (Exception e) {
                log.error("[EvaluationService] 评测任务失败: {}", e.getMessage(), e);
                runStatusMap.put(runId, new RunStatus(runId, "failed", cases.size(),
                    new AtomicInteger(0), "", e.getMessage(), null));
            }
        });
        return runId;
    }

    /** 同步执行评测（在后台线程中运行） */
    private EvaluationReport runSync(EvaluationRunRequest request, String runId,
                                      RunStatus status) {
        List<EvaluationCase> cases = request.cases() != null ? request.cases() : List.of();
        if (cases.isEmpty()) {
            return EvaluationReport.empty("空评测集");
        }
        long started = System.currentTimeMillis();
        List<EvaluationCaseResult> results = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            EvaluationCase c = cases.get(i);
            // 更新进度
            RunStatus current = runStatusMap.get(runId);
            if (current != null) {
                runStatusMap.put(runId, new RunStatus(
                    runId, "running", cases.size(), new AtomicInteger(i),
                    safe(c.question()), null, null));
            }
            try {
                EvaluationCaseResult result = runCaseSync(c, i + 1);
                results.add(result);
            } catch (Exception e) {
                log.warn("[EvaluationService] 用例 {} 执行失败: {}", i + 1, e.getMessage());
                results.add(new EvaluationCaseResult(
                    idOrDefault(c.id(), i + 1), safe(c.question()),
                    safe(c.referenceAnswer()),
                    c.expectedSources() != null ? c.expectedSources() : List.of(),
                    c.tags() != null ? c.tags() : List.of(),
                    "", List.of(),
                    new RetrievalEval(false, 0, 0, List.of(), List.of()),
                    JudgeResult.failed(e.getMessage()),
                    0, e.getMessage()));
            }
        }
        return buildReport(runId, request.name(), started, results);
    }

    /** 同步执行单条用例（RAG 检索 + 生成 + 裁判评分） */
    private EvaluationCaseResult runCaseSync(EvaluationCase testCase, int order) {
        long started = System.currentTimeMillis();
        String question = safe(testCase.question());
        var debug = ragPipeline.retrieveWithTrace(question, List.of()).block();
        RAGPipeline.RAGContext context = debug != null ? debug.context() : null;
        List<RetrievedDocument> docs = context != null && context.documents() != null
            ? context.documents() : List.of();
        String answer = chatService.chatWithRagContext(
            question, List.of(), context, UsageScenario.EVALUATION_ANSWER).block();
        RetrievalEval retrievalEval = evaluateRetrieval(testCase, docs);
        JudgeResult judge = judgeSync(testCase, answer != null ? answer : "", docs, retrievalEval);
        return new EvaluationCaseResult(
            idOrDefault(testCase.id(), order), question,
            safe(testCase.referenceAnswer()),
            testCase.expectedSources() != null ? testCase.expectedSources() : List.of(),
            testCase.tags() != null ? testCase.tags() : List.of(),
            answer != null ? answer : "", docSummaries(docs),
            retrievalEval, judge,
            System.currentTimeMillis() - started, null);
    }

    /** 同步裁判评分 */
    private JudgeResult judgeSync(EvaluationCase testCase, String answer,
                                   List<RetrievedDocument> docs, RetrievalEval retrievalEval) {
        try {
            var modelConfig = aiProperties.getModelConfig().block();
            var chatConfig = aiProperties.getChatConfig()
                .defaultIfEmpty(new AIProperties.ChatConfig()).block();
            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", judgeSystemPrompt()),
                Map.of("role", "user", "content", judgeUserPrompt(testCase, answer, docs, retrievalEval))
            );
            int maxTokens = chatConfig != null
                ? Math.min(1200, Math.max(512, chatConfig.getMaxTokens())) : 800;
            String json = llmClient.chatInternal(
                modelConfig.getEffectiveChatModel(), messages, 0.0f, maxTokens,
                Map.of("type", "json_object"), UsageScenario.EVALUATION_JUDGE).block();
            return parseJudgeResult(json != null ? json : "");
        } catch (Exception e) {
            log.warn("[EvaluationService] 裁判评分失败: {}", e.getMessage());
            return JudgeResult.failed(e.getMessage());
        }
    }

    /** 查询任务状态 */
    public RunStatus getStatus(String runId) {
        return runStatusMap.get(runId);
    }

    public Mono<EvaluationReport> run(EvaluationRunRequest request) {
        List<EvaluationCase> cases = request.cases() != null ? request.cases() : List.of();
        if (cases.isEmpty()) {
            return Mono.just(EvaluationReport.empty("空评测集"));
        }

        long started = System.currentTimeMillis();
        String runId = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        return Flux.fromIterable(cases)
            .index()
            .concatMap(tuple -> runCase(tuple.getT2(), tuple.getT1().intValue() + 1))
            .collectList()
            .map(results -> buildReport(runId, request.name(), started, results));
    }

    private Mono<EvaluationCaseResult> runCase(EvaluationCase testCase, int order) {
        long started = System.currentTimeMillis();
        String question = safe(testCase.question());
        return ragPipeline.retrieveWithTrace(question, List.of())
            .flatMap(debug -> chatService.chatWithRagContext(
                    question,
                    List.of(),
                    debug.context(),
                    UsageScenario.EVALUATION_ANSWER)
                .flatMap(answer -> {
                List<RetrievedDocument> docs = debug.context().documents() != null
                    ? debug.context().documents() : List.of();
                RetrievalEval retrievalEval = evaluateRetrieval(testCase, docs);
                return judge(testCase, answer, docs, retrievalEval)
                    .map(judge -> new EvaluationCaseResult(
                        idOrDefault(testCase.id(), order),
                        question,
                        safe(testCase.referenceAnswer()),
                        testCase.expectedSources() != null ? testCase.expectedSources() : List.of(),
                        testCase.tags() != null ? testCase.tags() : List.of(),
                        answer,
                        docSummaries(docs),
                        retrievalEval,
                        judge,
                        System.currentTimeMillis() - started,
                        null
                    ));
                }))
            .onErrorResume(e -> {
                log.warn("[EvaluationService] 评测用例执行失败: {}", e.getMessage());
                return Mono.just(new EvaluationCaseResult(
                    idOrDefault(testCase.id(), order),
                    question,
                    safe(testCase.referenceAnswer()),
                    testCase.expectedSources() != null ? testCase.expectedSources() : List.of(),
                    testCase.tags() != null ? testCase.tags() : List.of(),
                    "",
                    List.of(),
                    new RetrievalEval(false, 0, 0, List.of(), List.of()),
                    JudgeResult.failed(e.getMessage()),
                    System.currentTimeMillis() - started,
                    e.getMessage()
                ));
            });
    }

    private Mono<JudgeResult> judge(EvaluationCase testCase, String answer,
                                    List<RetrievedDocument> docs, RetrievalEval retrievalEval) {
        return Mono.zip(
            aiProperties.getModelConfig(),
            aiProperties.getChatConfig().defaultIfEmpty(new AIProperties.ChatConfig())
        ).flatMap(tuple -> {
            AIProperties.ModelConfig modelConfig = tuple.getT1();
            AIProperties.ChatConfig chatConfig = tuple.getT2();
            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", judgeSystemPrompt()),
                Map.of("role", "user", "content", judgeUserPrompt(testCase, answer, docs, retrievalEval))
            );
            return llmClient.chatInternal(
                    modelConfig.getEffectiveChatModel(),
                    messages,
                    0.0f,
                    Math.min(1200, Math.max(512, chatConfig.getMaxTokens())),
                    Map.of("type", "json_object"),
                    UsageScenario.EVALUATION_JUDGE
                )
                .map(this::parseJudgeResult)
                .onErrorResume(e -> {
                    log.warn("[EvaluationService] 裁判评分失败: {}", e.getMessage());
                    return Mono.just(JudgeResult.failed(e.getMessage()));
                });
        });
    }

    private String judgeSystemPrompt() {
        return """
            你是 RAG 问答系统的效果评测裁判。请只输出合法 JSON。
            评分范围是 1 到 5，5 代表优秀，1 代表失败。
            维度：
            - relevance: 回答是否针对问题
            - correctness: 与参考答案是否一致
            - faithfulness: 回答是否能被检索上下文支撑，不能凭空编造
            - completeness: 是否覆盖关键要点
            - citationAccuracy: 引用或依据是否匹配检索结果
            最后给出 hallucinationRisk: low / medium / high，以及 concise reason。
            JSON schema:
            {"relevance":5,"correctness":5,"faithfulness":5,"completeness":5,"citationAccuracy":5,"hallucinationRisk":"low","reason":"..."}
            """;
    }

    private String judgeUserPrompt(EvaluationCase testCase, String answer,
                                   List<RetrievedDocument> docs, RetrievalEval retrievalEval) {
        String context = docs.stream()
            .limit(8)
            .map(doc -> "- 标题: %s\n  postId: %s\n  chunk: %d\n  内容: %s"
                .formatted(safe(doc.postTitle()), safe(doc.postId()), doc.chunkIndex(),
                    truncate(safe(doc.content()), 800)))
            .reduce("", (a, b) -> a + b + "\n");
        return """
            问题：
            %s

            参考答案：
            %s

            期望来源：
            %s

            检索命中：
            hit=%s, rank=%d, precisionAt5=%.2f

            检索上下文：
            %s

            待评回答：
            %s
            """.formatted(
                safe(testCase.question()),
                safe(testCase.referenceAnswer()),
                testCase.expectedSources() != null ? testCase.expectedSources() : List.of(),
                retrievalEval.hit(),
                retrievalEval.firstHitRank(),
                retrievalEval.precisionAt5(),
                context.isBlank() ? "无" : context,
                safe(answer)
            );
    }

    private JudgeResult parseJudgeResult(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            int relevance = clampScore(root.path("relevance").asInt(1));
            int correctness = clampScore(root.path("correctness").asInt(1));
            int faithfulness = clampScore(root.path("faithfulness").asInt(1));
            int completeness = clampScore(root.path("completeness").asInt(1));
            int citationAccuracy = clampScore(root.path("citationAccuracy").asInt(1));
            String risk = root.path("hallucinationRisk").asText("medium");
            String reason = root.path("reason").asText("");
            double total = weightedScore(relevance, correctness, faithfulness, completeness, citationAccuracy);
            return new JudgeResult(relevance, correctness, faithfulness, completeness,
                citationAccuracy, risk, reason, total, null);
        } catch (Exception e) {
            return JudgeResult.failed("解析裁判 JSON 失败: " + e.getMessage());
        }
    }

    private EvaluationReport buildReport(String runId, String name, long started,
                                         List<EvaluationCaseResult> results) {
        int total = results.size();
        double avgTotal = avg(results.stream().map(r -> r.judge().totalScore()).toList());
        double avgRelevance = avg(results.stream().map(r -> (double) r.judge().relevance()).toList());
        double avgCorrectness = avg(results.stream().map(r -> (double) r.judge().correctness()).toList());
        double avgFaithfulness = avg(results.stream().map(r -> (double) r.judge().faithfulness()).toList());
        double avgCompleteness = avg(results.stream().map(r -> (double) r.judge().completeness()).toList());
        double avgCitation = avg(results.stream().map(r -> (double) r.judge().citationAccuracy()).toList());
        long hitCount = results.stream().filter(r -> r.retrieval().hit()).count();
        long highRisk = results.stream()
            .filter(r -> "high".equalsIgnoreCase(r.judge().hallucinationRisk()))
            .count();
        long failed = results.stream().filter(r -> r.error() != null || r.judge().error() != null).count();
        EvaluationSummary summary = new EvaluationSummary(
            total,
            round(avgTotal),
            round(avgRelevance),
            round(avgCorrectness),
            round(avgFaithfulness),
            round(avgCompleteness),
            round(avgCitation),
            total == 0 ? 0 : round(hitCount * 100.0 / total),
            total == 0 ? 0 : round(highRisk * 100.0 / total),
            failed
        );
        return new EvaluationReport(
            runId,
            name == null || name.isBlank() ? "即时评测" : name,
            Instant.ofEpochMilli(started).toString(),
            System.currentTimeMillis() - started,
            summary,
            results
        );
    }

    private RetrievalEval evaluateRetrieval(EvaluationCase testCase, List<RetrievedDocument> docs) {
        List<String> expected = testCase.expectedSources() != null ? testCase.expectedSources() : List.of();
        if (expected.isEmpty()) {
            return new RetrievalEval(!docs.isEmpty(), docs.isEmpty() ? 0 : 1, docs.isEmpty() ? 0 : 1,
                List.of(), docs.stream().limit(5).map(this::docKey).toList());
        }

        List<String> normalizedExpected = expected.stream()
            .map(this::normalize)
            .filter(s -> !s.isBlank())
            .toList();
        List<String> matched = new ArrayList<>();
        int firstHitRank = 0;
        int relevantAt5 = 0;
        for (int i = 0; i < docs.size(); i++) {
            RetrievedDocument doc = docs.get(i);
            boolean match = matchesAny(doc, normalizedExpected);
            if (match) {
                matched.add(docKey(doc));
                if (firstHitRank == 0) {
                    firstHitRank = i + 1;
                }
                if (i < 5) {
                    relevantAt5++;
                }
            }
        }
        return new RetrievalEval(
            firstHitRank > 0,
            firstHitRank,
            Math.min(1.0, relevantAt5 / 5.0),
            matched.stream().distinct().toList(),
            docs.stream().limit(5).map(this::docKey).toList()
        );
    }

    private boolean matchesAny(RetrievedDocument doc, List<String> expected) {
        String id = normalize(doc.postId());
        String title = normalize(doc.postTitle());
        return expected.stream().anyMatch(exp ->
            !exp.isBlank() && (id.contains(exp) || exp.contains(id) || title.contains(exp) || exp.contains(title))
        );
    }

    private List<Map<String, Object>> docSummaries(List<RetrievedDocument> docs) {
        return docs.stream()
            .limit(10)
            .map(doc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("postId", doc.postId());
                m.put("title", doc.postTitle());
                m.put("chunkIndex", doc.chunkIndex());
                m.put("score", doc.score());
                m.put("content", truncate(safe(doc.content()), 320));
                return m;
            })
            .toList();
    }

    private String docKey(RetrievedDocument doc) {
        String title = safe(doc.postTitle());
        String id = safe(doc.postId());
        return title.isBlank() ? id : title + (id.isBlank() ? "" : " (" + id + ")");
    }

    private double weightedScore(int relevance, int correctness, int faithfulness,
                                 int completeness, int citationAccuracy) {
        return round(0.20 * relevance + 0.25 * correctness + 0.30 * faithfulness
            + 0.15 * completeness + 0.10 * citationAccuracy);
    }

    private double avg(List<Double> values) {
        if (values.isEmpty()) return 0;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double round(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    private int clampScore(int score) {
        return Math.max(1, Math.min(5, score));
    }

    private String idOrDefault(String id, int order) {
        return id != null && !id.isBlank() ? id : "case-" + order;
    }

    private String normalize(String s) {
        return safe(s).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return safe(s);
        return s.substring(0, max) + "...";
    }

    public record EvaluationRunRequest(String name, String datasetId, List<EvaluationCase> cases) {}

    public record EvaluationCase(
        String id,
        String question,
        String referenceAnswer,
        List<String> expectedSources,
        List<String> tags
    ) {}

    public record EvaluationReport(
        String runId,
        String name,
        String startedAt,
        long durationMs,
        EvaluationSummary summary,
        List<EvaluationCaseResult> results
    ) {
        static EvaluationReport empty(String message) {
            return new EvaluationReport("eval-" + UUID.randomUUID().toString().substring(0, 8),
                message, Instant.now().toString(), 0,
                new EvaluationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of());
        }
    }

    public record EvaluationSummary(
        int totalCases,
        double avgScore,
        double relevance,
        double correctness,
        double faithfulness,
        double completeness,
        double citationAccuracy,
        double retrievalHitRate,
        double hallucinationHighRiskRate,
        long failedCases
    ) {}

    public record EvaluationCaseResult(
        String id,
        String question,
        String referenceAnswer,
        List<String> expectedSources,
        List<String> tags,
        String answer,
        List<Map<String, Object>> retrievedDocuments,
        RetrievalEval retrieval,
        JudgeResult judge,
        long latencyMs,
        String error
    ) {}

    public record RetrievalEval(
        boolean hit,
        int firstHitRank,
        double precisionAt5,
        List<String> matchedSources,
        List<String> topSources
    ) {}

    public record JudgeResult(
        int relevance,
        int correctness,
        int faithfulness,
        int completeness,
        int citationAccuracy,
        String hallucinationRisk,
        String reason,
        double totalScore,
        String error
    ) {
        static JudgeResult failed(String error) {
            return new JudgeResult(1, 1, 1, 1, 1, "high", "", 1, error);
        }
    }
}

package cn.rainwu.halo.ai.suite.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import cn.rainwu.halo.ai.suite.service.ChatLogger;
import cn.rainwu.halo.ai.suite.service.ChatLogger.FeedbackFilter;
import cn.rainwu.halo.ai.suite.service.ChatLogger.LogFilter;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 内容缺口分析 Agent.
 *
 * <p>MVP 形态: 同步读取最近访客问答 + 公开文章标题, 让 LLM 产出一份运营建议报告。
 * 这里不写文章、不改配置, 后续可以在同一个 service 外围挂任务记录与人工确认流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentGapAgentService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_LOGS_LIMIT = 100;

    private final ChatLogger chatLogger;
    private final ReactiveExtensionClient extensionClient;
    private final AIProperties aiProperties;
    private final LlmClient llmClient;

    public Mono<Map<String, Object>> run(int days, int maxLogs, int maxTokens) {
        return run(days, maxLogs, maxTokens, (progress, step) -> {});
    }

    public Mono<Map<String, Object>> run(int days, int maxLogs, int maxTokens,
                                         BiConsumer<Integer, String> progressCallback) {
        int safeDays = Math.min(180, Math.max(1, days));
        int safeMaxLogs = Math.min(MAX_LOGS_LIMIT, Math.max(10, maxLogs));
        int safeMaxTokens = Math.min(8000, Math.max(1000, maxTokens));
        Instant to = Instant.now();
        Instant from = to.minusSeconds(safeDays * 24L * 3600L);

        Mono<List<ChatLogger.ChatLogEntry>> logsMono = chatLogger
            .listLogs(new LogFilter(from, to, null, FeedbackFilter.ALL, null), 0, safeMaxLogs)
            .map(ChatLogger.PageResult::items);

        Mono<List<Map<String, Object>>> postsMono = extensionClient.list(Post.class,
                post -> post.isPublished()
                    && !post.isDeleted()
                    && post.getSpec() != null
                    && Boolean.TRUE.equals(post.getSpec().getPublish())
                    && Post.isPublic(post.getSpec()),
                null)
            .map(this::postSummary)
            .collectList();

        progressCallback.accept(15, "收集数据");

        return Mono.zip(logsMono, postsMono, aiProperties.getModelConfig())
            .flatMap(tuple -> {
                List<ChatLogger.ChatLogEntry> logs = tuple.getT1();
                List<Map<String, Object>> posts = tuple.getT2();
                AIProperties.ModelConfig model = tuple.getT3();
                progressCallback.accept(40, "准备输入");
                Map<String, Object> input = buildInput(safeDays, safeMaxLogs, logs, posts);

                List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt()),
                    Map.of("role", "user", "content", userPrompt(input))
                );
                progressCallback.accept(70, "调用模型");
                return llmClient.chatInternal(
                        model.getEffectiveChatModel(),
                        messages,
                        0.3f,
                        safeMaxTokens,
                        Map.of("type", "json_object"),
                        UsageScenario.AGENT_CONTENT_GAP
                    )
                    .doOnNext(answer -> progressCallback.accept(85, "解析保存"))
                    .map(answer -> {
                        Map<String, Object> parsed = parseReport(input, answer);
                        progressCallback.accept(92, "解析保存");
                        return parsed;
                    })
                    .onErrorResume(e -> {
                        log.warn("[ContentGapAgent] 运行失败: {}", e.getMessage());
                        return Mono.just(errorReport(input, e.getMessage()));
                    });
            });
    }

    private Map<String, Object> buildInput(int days, int maxLogs,
                                           List<ChatLogger.ChatLogEntry> logs,
                                           List<Map<String, Object>> posts) {
        List<Map<String, Object>> compactLogs = logs.stream()
            .map(log -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("time", log.timestamp() != null ? log.timestamp().toString() : "");
                item.put("question", trim(log.question(), 180));
                item.put("answer", trim(log.answer(), 180));
                item.put("feedback", log.feedback() != null ? log.feedback().type() : "none");
                item.put("feedbackComment", log.feedback() != null ? trim(log.feedback().comment(), 160) : "");
                item.put("citationCount", log.citations() != null ? log.citations().size() : 0);
                item.put("citations", log.citations() != null ? log.citations().stream()
                    .limit(3)
                    .map(c -> c.getOrDefault("title", ""))
                    .filter(s -> s != null && !s.isBlank())
                    .toList() : List.of());
                return item;
            })
            .toList();

        long disliked = logs.stream()
            .filter(log -> log.feedback() != null && "dislike".equals(log.feedback().type()))
            .count();
        long noCitation = logs.stream()
            .filter(log -> log.citations() == null || log.citations().isEmpty())
            .count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("days", days);
        metrics.put("sampledLogs", logs.size());
        metrics.put("maxLogs", maxLogs);
        metrics.put("publishedPosts", posts.size());
        metrics.put("dislikedAnswers", disliked);
        metrics.put("noCitationAnswers", noCitation);
        metrics.put("searchQuerySource", "not_available");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("metrics", metrics);
        input.put("logs", compactLogs);
        input.put("posts", posts.stream().limit(80).toList());
        input.put("generatedAt", Instant.now().toString());
        return input;
    }

    private Map<String, Object> postSummary(Post post) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", post.getMetadata() != null ? post.getMetadata().getName() : "");
        item.put("title", post.getSpec() != null ? post.getSpec().getTitle() : "");
        item.put("createdAt", post.getMetadata() != null && post.getMetadata().getCreationTimestamp() != null
            ? post.getMetadata().getCreationTimestamp().toString() : "");
        item.put("updatedAt", post.getStatus() != null && post.getStatus().getLastModifyTime() != null
            ? post.getStatus().getLastModifyTime().toString() : "");
        return item;
    }

    private String systemPrompt() {
        return """
            你是一个博客内容运营智能体, 目标是基于访客问答记录和现有文章列表发现内容缺口。
            你必须只依据输入数据给出判断; 如果数据不足, 要明确说明。
            请输出严格 JSON, 不要 Markdown, 不要代码块。
            输出必须简洁: contentGaps 最多 5 条, articleUpdates 最多 3 条, nextActions 最多 5 条, limitations 最多 3 条。
            每个 evidence 最多 2 条, 每个 outline 最多 4 条。所有字符串尽量控制在 120 个中文字符以内。
            JSON schema:
            {
              "summary": "一句话总结",
              "steps": [{"name":"步骤名","status":"done","detail":"发现"}],
              "contentGaps": [{
                "title":"缺口主题",
                "priority":"high|medium|low",
                "type":"new_article|update_article|faq|internal_link",
                "evidence":["来自访客问题或反馈的证据"],
                "reason":"为什么这是缺口",
                "suggestedAction":"建议动作",
                "outline":["大纲点1","大纲点2","大纲点3"]
              }],
              "articleUpdates": [{
                "articleTitle":"可能需要更新的文章标题",
                "reason":"为什么要更新",
                "suggestion":"怎么改"
              }],
              "nextActions":["优先动作1","优先动作2","优先动作3"],
              "limitations":["数据限制"]
            }
            """;
    }

    private String userPrompt(Map<String, Object> input) {
        try {
            return "请分析以下博客运营数据并输出简洁 JSON 报告。不要输出 schema 以外字段:\n"
                + objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return "请分析以下博客运营数据并输出 JSON 报告:\n" + input;
        }
    }

    private Map<String, Object> parseReport(Map<String, Object> input, String answer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("template", "content-gap");
        body.put("input", input);
        try {
            String json = stripJson(answer);
            Map<String, Object> report = readReportJson(json);
            body.put("report", report);
        } catch (Exception e) {
            body.put("success", false);
            body.put("error", friendlyJsonError(e.getMessage()));
            body.put("technicalError", e.getMessage());
            body.put("raw", answer);
            body.put("report", fallbackReport(input, "模型返回格式异常。"));
        }
        return body;
    }

    private String friendlyJsonError(String detail) {
        String text = detail != null ? detail : "";
        if (text.contains("Unexpected end-of-input") || text.contains("expected close marker")) {
            return "模型返回的报告内容不完整，JSON 结构被截断了。可以调高“输出上限”，或减少问答样本后重新运行。";
        }
        if (text.contains("Unexpected character") || text.contains("Unexpected close marker")) {
            return "模型返回的报告格式不符合 JSON 要求。请重新运行一次，或降低样本数量让输出更短。";
        }
        return "模型返回的报告格式无法解析。请重新运行一次，或调高输出上限后再试。";
    }

    private Map<String, Object> readReportJson(String json) throws Exception {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception first) {
            String repaired = repairTruncatedJson(json);
            return objectMapper.readValue(repaired, new TypeReference<>() {});
        }
    }

    private Map<String, Object> errorReport(Map<String, Object> input, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("template", "content-gap");
        body.put("input", input);
        body.put("error", error != null ? error : "未知错误");
        body.put("report", fallbackReport(input, error));
        return body;
    }

    private Map<String, Object> fallbackReport(Map<String, Object> input, String reason) {
        Map<String, Object> report = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) input.getOrDefault("metrics", Map.of());
        report.put("summary", "暂未生成完整智能分析报告。");
        report.put("steps", List.of(
            Map.of("name", "收集访客问答", "status", "done", "detail", "样本数: " + metrics.getOrDefault("sampledLogs", 0)),
            Map.of("name", "读取公开文章", "status", "done", "detail", "文章数: " + metrics.getOrDefault("publishedPosts", 0)),
            Map.of("name", "生成运营建议", "status", "failed", "detail", reason != null ? reason : "模型调用失败")
        ));
        report.put("contentGaps", List.of());
        report.put("articleUpdates", List.of());
        report.put("nextActions", List.of("检查模型配置后重新运行", "确认最近 30 天是否已有访客问答记录"));
        report.put("limitations", List.of("当前版本尚未接入站内搜索词日志", "MVP 只生成报告, 不自动修改文章"));
        return report;
    }

    private String stripJson(String text) {
        if (text == null) return "{}";
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private String repairTruncatedJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        StringBuilder out = new StringBuilder(json.trim());
        boolean inString = false;
        boolean escaped = false;
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();

        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                stack.push('}');
            } else if (c == '[') {
                stack.push(']');
            } else if ((c == '}' || c == ']') && !stack.isEmpty() && stack.peek() == c) {
                stack.pop();
            }
        }

        if (inString) {
            out.append('"');
        }
        trimDanglingToken(out);
        while (!stack.isEmpty()) {
            char closer = stack.pop();
            removeTrailingComma(out);
            out.append(closer);
        }
        return out.toString();
    }

    private void trimDanglingToken(StringBuilder out) {
        int i = out.length() - 1;
        while (i >= 0 && Character.isWhitespace(out.charAt(i))) i--;
        if (i < 0) return;
        char c = out.charAt(i);
        if (c == ':' || c == ',') {
            out.delete(i, out.length());
        }
    }

    private void removeTrailingComma(StringBuilder out) {
        int i = out.length() - 1;
        while (i >= 0 && Character.isWhitespace(out.charAt(i))) i--;
        if (i >= 0 && out.charAt(i) == ',') {
            out.delete(i, out.length());
        }
    }

    private String trim(String text, int max) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").trim();
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}

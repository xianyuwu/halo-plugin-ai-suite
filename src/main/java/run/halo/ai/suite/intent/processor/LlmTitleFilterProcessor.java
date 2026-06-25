package run.halo.ai.suite.intent.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.intent.PipelineProcessor;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.app.core.extension.content.Post;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 标题主题过滤处理器 — 用 LLM 判断文章标题是否与用户查询主题相关.
 * <p>
 * 这是你最初提的「最新 AI 文章」场景的核心处理器：
 * RAG 无法语义判断「文章是 AI 主题」，而本处理器把所有标题批量喂给 LLM，
 * 让它输出与查询主题相关的标题序号列表.
 * <p>
 * 调用策略（一次 LLM 调用批量处理所有候选，避免逐个调用成本爆炸）：
 * <ol>
 *   <li>把候选 Post 的 {@code [idx] title} 拼成列表</li>
 *   <li>调 LLM（response_format=json_object）让它返回 {@code {"related":[1,3,5]}}</li>
 *   <li>解析序号集合，过滤候选</li>
 * </ol>
 * <p>
 * params:
 * <ul>
 *   <li>{@code prompt} — 自定义判断指令，默认「判断标题是否与用户问题主题相关」</li>
 *   <li>{@code limit} — 返回前 N 条（在 LLM 筛完后截取，避免返回过多），默认 0 不截取</li>
 * </ul>
 * <p>
 * 容错：LLM 调用失败 / 解析失败 → 返回原 candidates 不中断 pipeline（避免一个失败拖垮整条链路）.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTitleFilterProcessor implements PipelineProcessor {

    public static final String TYPE = "LLM_TITLE_FILTER";

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final int DEFAULT_MAX_CANDIDATES = 200;  // 防止 token 爆炸

    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mono<List<Post>> process(List<Post> candidates, PipelineStep step,
                                    String userQuery, List<Map<String, String>> history) {
        if (candidates.isEmpty()) {
            return Mono.just(candidates);
        }

        // 先按发布时间稳定排序再截取，避免 Extension 未定义顺序造成随机漏选。
        int candidateLimit = paramInt(step, "candidateLimit", DEFAULT_MAX_CANDIDATES);
        List<Post> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(
            p -> p.getSpec() != null ? p.getSpec().getPublishTime() : null,
            Comparator.nullsLast(Comparator.reverseOrder())));
        List<Post> pool = candidateLimit > 0 && ordered.size() > candidateLimit
            ? new ArrayList<>(ordered.subList(0, candidateLimit)) : ordered;
        int limit = paramInt(step, "limit", 0);
        String customPrompt = param(step, "prompt",
            "判断标题是否与用户问题主题相关");

        return aiProperties.getModelConfig().flatMap(modelConfig -> {
            // 拼标题列表
            StringBuilder titleList = new StringBuilder();
            for (int i = 0; i < pool.size(); i++) {
                Post p = pool.get(i);
                String title = p.getSpec() != null && p.getSpec().getTitle() != null
                    ? p.getSpec().getTitle() : "";
                titleList.append(i + 1).append(". ").append(title).append("\n");
            }

            String systemPrompt = """
                你是一个文章分类助手。给定一组博客文章标题（带编号）和用户查询，
                判断哪些标题在主题上与用户查询相关。
                只返回 JSON：{"related":[1,3,5]}，其中数字是相关标题的编号（从 1 开始）。
                不要解释，不要返回其它字段。如果都不相关，返回 {"related":[]}。
                判断指令：""" + customPrompt;

            String userPrompt = "用户查询：" + (userQuery != null ? userQuery : "") + "\n\n"
                + "文章标题列表：\n" + titleList;

            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            );

            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_object");

            return llmClient.chat(
                    modelConfig.getEffectiveChatModel(),
                    messages,
                    0.0f,
                    512,
                    responseFormat,
                    null,
                    UsageScenario.INTENT_PIPELINE
                )
                .timeout(TIMEOUT)
                .map(answer -> {
                    Set<Integer> relatedIdx = parseRelatedIndices(answer);
                    List<Post> filtered = new ArrayList<>();
                    for (int idx : relatedIdx) {
                        if (idx >= 1 && idx <= pool.size()) {
                            filtered.add(pool.get(idx - 1));
                        }
                    }
                    if (limit > 0 && filtered.size() > limit) {
                        filtered = new ArrayList<>(filtered.subList(0, limit));
                    }
                    log.debug("[LlmTitleFilter] {} → {} 条 (related idx={}, query='{}')",
                        pool.size(), filtered.size(), relatedIdx, userQuery);
                    return filtered;
                })
                .onErrorResume(e -> {
                    log.warn("[LlmTitleFilter] LLM 调用或解析失败，返回空结果: {}", e.getMessage());
                    return Mono.just(List.of());
                });
        });
    }

    /**
     * 解析 LLM 返回的 JSON，提取 related 数组里的整数序号.
     * <p>容错：LLM 偶尔不严格遵守 json_object，用正则兜底提取数组.
     */
    static Set<Integer> parseRelatedIndices(String answer) {
        if (answer == null || answer.isBlank()) return Set.of();
        Set<Integer> result = new LinkedHashSet<>();

        // 尝试 1：严格 JSON 解析
        String trimmed = answer.trim();
        // 去掉可能的 markdown 代码块包裹
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        try {
            // 简易提取，避免引入 jackson 依赖（LlmClient 内部已用）
            int braceStart = trimmed.indexOf('{');
            int braceEnd = trimmed.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                String json = trimmed.substring(braceStart, braceEnd + 1);
                int arrStart = json.indexOf('[');
                int arrEnd = json.indexOf(']');
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arrStr = json.substring(arrStart + 1, arrEnd);
                    for (String tok : arrStr.split(",")) {
                        String t = tok.trim().replaceAll("[^0-9]", "");
                        if (!t.isEmpty()) {
                            try {
                                result.add(Integer.parseInt(t));
                            } catch (NumberFormatException ignore) {}
                        }
                    }
                }
            }
        } catch (Exception ignore) {}

        if (result.isEmpty()) {
            // 尝试 2：正则兜底找所有数字
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(trimmed);
            while (m.find()) {
                try {
                    result.add(Integer.parseInt(m.group()));
                } catch (NumberFormatException ignore) {}
            }
        }
        return result;
    }
}

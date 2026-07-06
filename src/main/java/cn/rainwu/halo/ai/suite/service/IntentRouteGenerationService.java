package cn.rainwu.halo.ai.suite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.extension.IntentRoute.PipelineStep;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import cn.rainwu.halo.ai.suite.service.IntentRouteService.IntentRouteDto;
import cn.rainwu.halo.ai.suite.service.IntentRouteService.SaveIntentRequest;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ReactiveExtensionClient;

/** 通过自然语言生成受约束的 IntentRoute 草稿，不直接保存或启用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouteGenerationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FILTER_TYPES = Set.of(
        "TOPIC_MATCH", "LLM_TITLE_FILTER", "TAG_MATCH", "KEYWORD_MATCH", "CATEGORY_MATCH");

    private final AIProperties aiProperties;
    private final LlmClient llmClient;
    private final IntentRouteService intentRouteService;
    private final ReactiveExtensionClient client;

    public Mono<GenerationResult> generate(String requirement) {
        String normalized = requirement == null ? "" : requirement.trim();
        if (normalized.isEmpty()) {
            return Mono.error(new IllegalArgumentException("请描述希望创建的路由规则"));
        }
        if (normalized.length() > 2000) {
            return Mono.error(new IllegalArgumentException("需求描述不能超过 2000 个字符"));
        }

        Mono<List<String>> tags = client.list(Tag.class, tag -> true, null)
            .map(tag -> tag.getSpec() != null ? tag.getSpec().getDisplayName() : "")
            .filter(name -> name != null && !name.isBlank()).collectList();
        Mono<List<String>> categories = client.list(Category.class, category -> true, null)
            .map(category -> category.getSpec() != null ? category.getSpec().getDisplayName() : "")
            .filter(name -> name != null && !name.isBlank()).collectList();

        return Mono.zip(aiProperties.getModelConfig(), intentRouteService.listIntents(), tags, categories)
            .flatMap(tuple -> {
                String context = buildContext(tuple.getT2(), tuple.getT3(), tuple.getT4());
                List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt()),
                    Map.of("role", "user", "content", context + "\n\n管理员需求：\n" + normalized)
                );
                return llmClient.chatInternal(
                    tuple.getT1().getEffectiveChatModel(), messages, 0.2f, 2400,
                    Map.of("type", "json_object"), UsageScenario.INTENT_ROUTE_GENERATE)
                    .map(this::parseDraft)
                    .map(draft -> {
                        intentRouteService.validateDraft(draft);
                        List<ValidationIssue> issues = inspect(draft, tuple.getT2(),
                            tuple.getT3(), tuple.getT4());
                        return new GenerationResult(draft, issues, explain(draft), List.of());
                    });
            })
            .onErrorMap(e -> e instanceof IllegalArgumentException ? e
                : new IllegalArgumentException("AI 生成失败：" + friendly(e.getMessage()), e));
    }

    public List<ValidationIssue> inspectDraft(SaveIntentRequest draft) {
        intentRouteService.validateDraft(draft);
        return inspect(draft, List.of(), List.of(), List.of());
    }

    private SaveIntentRequest parseDraft(String answer) {
        try {
            String json = stripJson(answer);
            JsonNode root = MAPPER.readTree(json);
            List<String> triggers = new ArrayList<>();
            root.path("triggerPatterns").forEach(node -> triggers.add(node.asText()));
            List<PipelineStep> pipeline = new ArrayList<>();
            root.path("pipeline").forEach(node -> {
                PipelineStep step = new PipelineStep();
                step.setType(node.path("type").asText(""));
                Map<String, String> params = new LinkedHashMap<>();
                node.path("params").fields().forEachRemaining(entry ->
                    params.put(entry.getKey(), entry.getValue().asText()));
                step.setParams(normalizeParams(step.getType(), params));
                pipeline.add(step);
            });
            return new SaveIntentRequest(
                text(root, "id"), text(root, "displayName"), text(root, "description"),
                false, root.path("priority").asInt(50), triggers,
                root.path("llmFallback").asBoolean(false), text(root, "llmFallbackHint"),
                pipeline, text(root, "outputTemplate"));
        } catch (Exception e) {
            throw new IllegalArgumentException("模型未返回有效的路由 JSON，请重新生成", e);
        }
    }

    private List<ValidationIssue> inspect(SaveIntentRequest draft, List<IntentRouteDto> existing,
                                           List<String> tags, List<String> categories) {
        List<ValidationIssue> issues = new ArrayList<>();
        if ((draft.triggerPatterns() == null || draft.triggerPatterns().isEmpty())
            && !Boolean.TRUE.equals(draft.llmFallback())) {
            issues.add(new ValidationIssue("error", "triggerPatterns", "没有触发词，也未开启 LLM 兜底，路由不会被命中"));
        }
        if (draft.triggerPatterns() != null) {
            for (String pattern : draft.triggerPatterns()) {
                if (pattern.length() <= 2 || "文章".equals(pattern) || "推荐".equals(pattern)) {
                    issues.add(new ValidationIssue("warning", "triggerPatterns", "触发词“" + pattern + "”较宽，可能拦截普通问答"));
                }
                for (IntentRouteDto route : existing) {
                    if (route.triggerPatterns().stream().anyMatch(pattern::equalsIgnoreCase)) {
                        issues.add(new ValidationIssue("warning", "triggerPatterns",
                            "触发词“" + pattern + "”与“" + route.displayName() + "”重复"));
                    }
                }
            }
        }
        List<PipelineStep> steps = draft.pipeline() != null ? draft.pipeline() : List.of();
        boolean sorted = false;
        int llmSteps = 0;
        for (PipelineStep step : steps) {
            if ("TIME_SORT".equals(step.getType()) || "VISIT_SORT".equals(step.getType())) sorted = true;
            if (FILTER_TYPES.contains(step.getType()) && sorted) {
                issues.add(new ValidationIssue("warning", "pipeline", "建议先筛选文章，再执行排序和数量限制"));
                sorted = false;
            }
            if ("TOPIC_MATCH".equals(step.getType()) || "LLM_TITLE_FILTER".equals(step.getType())) llmSteps++;
            validateFixedResource(step, tags, categories, issues);
            validateProcessorParams(step, issues);
        }
        if (llmSteps > 1) {
            issues.add(new ValidationIssue("warning", "pipeline", "包含多个 LLM 筛选步骤，可能增加延迟和 Token 消耗"));
        }
        return issues.stream().distinct().toList();
    }

    private void validateProcessorParams(PipelineStep step, List<ValidationIssue> issues) {
        Map<String, String> params = step.getParams() != null ? step.getParams() : Map.of();
        Set<String> allowed = switch (step.getType()) {
            case "TOPIC_MATCH" -> Set.of("prompt", "aliases", "candidateLimit", "limit", "onFailure");
            case "LLM_TITLE_FILTER" -> Set.of("prompt", "candidateLimit", "limit", "onFailure");
            case "TAG_MATCH" -> Set.of("mode", "tags", "onFailure");
            case "KEYWORD_MATCH" -> Set.of("mode", "fields", "keyword", "onFailure");
            case "CATEGORY_MATCH" -> Set.of("mode", "categories", "onFailure");
            case "TIME_SORT" -> Set.of("order", "limit", "onFailure");
            case "VISIT_SORT" -> Set.of("limit", "onFailure");
            default -> Set.of();
        };
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                issues.add(new ValidationIssue("error", "pipeline",
                    step.getType() + " 不支持参数“" + key + "”"));
            }
        }
    }

    private static Map<String, String> normalizeParams(String type, Map<String, String> source) {
        Map<String, String> params = new LinkedHashMap<>(source);
        if ("CATEGORY_MATCH".equals(type)) {
            move(params, "category", "categories");
            if (params.containsKey("categories")) params.putIfAbsent("mode", "fixed");
        } else if ("TAG_MATCH".equals(type)) {
            move(params, "tag", "tags");
            if (params.containsKey("tags")) params.putIfAbsent("mode", "fixed");
        } else if ("KEYWORD_MATCH".equals(type)) {
            move(params, "keywords", "keyword");
            if (params.containsKey("keyword")) params.putIfAbsent("mode", "fixed");
            params.putIfAbsent("fields", "title,content");
        } else if ("TIME_SORT".equals(type)) {
            params.putIfAbsent("order", "desc");
            params.putIfAbsent("limit", "10");
        } else if ("VISIT_SORT".equals(type)) {
            params.remove("order");
            params.putIfAbsent("limit", "10");
        } else if ("TOPIC_MATCH".equals(type) || "LLM_TITLE_FILTER".equals(type)) {
            params.putIfAbsent("candidateLimit", "200");
            params.putIfAbsent("onFailure", "empty");
        }
        return params;
    }

    private static void move(Map<String, String> params, String from, String to) {
        String value = params.remove(from);
        if (value != null && !value.isBlank()) params.putIfAbsent(to, value);
    }

    private void validateFixedResource(PipelineStep step, List<String> tags, List<String> categories,
                                       List<ValidationIssue> issues) {
        Map<String, String> params = step.getParams() != null ? step.getParams() : Map.of();
        if (!"fixed".equalsIgnoreCase(params.getOrDefault("mode", ""))) return;
        if ("TAG_MATCH".equals(step.getType())) {
            checkNames("标签", params.get("tags"), tags, issues);
        } else if ("CATEGORY_MATCH".equals(step.getType())) {
            checkNames("分类", params.get("categories"), categories, issues);
        }
    }

    private void checkNames(String type, String raw, List<String> existing,
                            List<ValidationIssue> issues) {
        if (raw == null || existing.isEmpty()) return;
        Set<String> names = existing.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        for (String value : raw.split("[,，、]")) {
            String name = value.trim();
            if (!name.isEmpty() && !names.contains(name.toLowerCase(Locale.ROOT))) {
                issues.add(new ValidationIssue("warning", "pipeline", type + "“" + name + "”在当前站点中不存在"));
            }
        }
    }

    private List<String> explain(SaveIntentRequest draft) {
        List<String> result = new ArrayList<>();
        result.add("使用 " + draft.triggerPatterns().size() + " 条触发规则识别访客意图");
        for (int i = 0; i < draft.pipeline().size(); i++) {
            result.add("第 " + (i + 1) + " 步执行 " + draft.pipeline().get(i).getType());
        }
        result.add("生成结果默认关闭，测试确认后再启用");
        return result;
    }

    private String buildContext(List<IntentRouteDto> routes, List<String> tags, List<String> categories) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("siteTags", tags.stream().limit(100).toList());
            context.put("siteCategories", categories.stream().limit(100).toList());
            context.put("existingRoutes", routes.stream().map(route -> Map.of(
                "id", route.id(), "name", route.displayName(), "priority", route.priority(),
                "triggers", route.triggerPatterns())).toList());
            return "当前站点信息：\n" + MAPPER.writeValueAsString(context);
        } catch (Exception e) {
            return "当前站点信息读取失败，请生成保守草稿。";
        }
    }

    private String systemPrompt() {
        return """
            你是 Halo 博客的意图路由配置助手。根据管理员需求生成严格 JSON，不要 Markdown 或解释。
            只能使用这些处理器：TOPIC_MATCH、LLM_TITLE_FILTER、TAG_MATCH、KEYWORD_MATCH、CATEGORY_MATCH、TIME_SORT、VISIT_SORT。
            Pipeline 必须先筛选再排序。优先使用确定性的标签、分类和排序；只有语义主题判断才使用 TOPIC_MATCH。
            fixed 模式只能使用输入中真实存在的标签或分类。正则应具体、简短，避免只用“文章”“推荐”等宽泛词。
            不支持的需求不要编造处理器，应生成最接近的安全草稿。
            输出 schema：
            {"id":"英文小写slug或空","displayName":"名称","description":"说明","priority":50,
             "triggerPatterns":["正则"],"llmFallback":false,"llmFallbackHint":"",
             "pipeline":[{"type":"处理器","params":{"参数":"字符串"}}],
             "outputTemplate":"回答格式要求，只能使用真实文章，不得编造"}
            所有 params 值必须是字符串。生成结果稍后由后端严格校验，默认不会启用。
            参数必须严格遵循：CATEGORY_MATCH={"mode":"fixed|from_query","categories":"分类1,分类2"}；
            TAG_MATCH={"mode":"fixed|from_query","tags":"标签1,标签2"}；
            KEYWORD_MATCH={"mode":"fixed|from_query","fields":"title,content","keyword":"关键词"}；
            TIME_SORT={"order":"desc|asc","limit":"10"}；VISIT_SORT={"limit":"10"}。
            """;
    }

    private static String stripJson(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) text = text.substring(firstNewline + 1, lastFence).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private static String text(JsonNode root, String name) {
        return root.path(name).asText("");
    }

    private static String friendly(String message) {
        if (message == null || message.isBlank()) return "模型服务暂时不可用";
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    public record ValidationIssue(String level, String field, String message) {}
    public record GenerationResult(SaveIntentRequest draft, List<ValidationIssue> issues,
                                   List<String> explanations, List<String> unsupportedRequirements) {}
}

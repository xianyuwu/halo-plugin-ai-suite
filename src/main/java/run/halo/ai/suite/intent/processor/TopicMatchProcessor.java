package run.halo.ai.suite.intent.processor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.ai.suite.intent.PipelineProcessor;
import run.halo.ai.suite.llm.LlmClient;
import run.halo.ai.suite.llm.UsageScenario;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 主题综合匹配处理器。
 *
 * <p>将标签/分类确定性命中与 LLM 对「标题 + 摘要 + 标签 + 分类」的语义判断
 * 做并集，避免标题不含主题词时误删已正确打标的文章。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicMatchProcessor implements PipelineProcessor {

    public static final String TYPE = "TOPIC_MATCH";
    private static final int DEFAULT_MAX_CANDIDATES = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(18);

    private final ReactiveExtensionClient client;
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

        int candidateLimit = paramInt(step, "candidateLimit", DEFAULT_MAX_CANDIDATES);
        int limit = paramInt(step, "limit", 0);
        String customPrompt = param(step, "prompt",
            "判断文章是否与用户问题中的主题相关");
        List<List<String>> aliasGroups = parseAliases(param(step, "aliases", ""));

        List<Post> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(
            p -> p.getSpec() != null ? p.getSpec().getPublishTime() : null,
            Comparator.nullsLast(Comparator.reverseOrder())));
        List<Post> pool = candidateLimit > 0 && ordered.size() > candidateLimit
            ? new ArrayList<>(ordered.subList(0, candidateLimit)) : ordered;

        Mono<Map<String, TopicLabel>> tags = client.list(Tag.class, tag -> true, null)
            .filter(tag -> tag.getMetadata() != null && tag.getMetadata().getName() != null)
            .collectMap(tag -> tag.getMetadata().getName(), tag -> new TopicLabel(
                tag.getSpec() != null ? tag.getSpec().getDisplayName() : "",
                tag.getSpec() != null ? tag.getSpec().getSlug() : ""));
        Mono<Map<String, TopicLabel>> categories = client.list(Category.class, category -> true, null)
            .filter(category -> category.getMetadata() != null
                && category.getMetadata().getName() != null)
            .collectMap(category -> category.getMetadata().getName(), category -> new TopicLabel(
                category.getSpec() != null ? category.getSpec().getDisplayName() : "",
                category.getSpec() != null ? category.getSpec().getSlug() : ""));

        return Mono.zip(tags, categories)
            .flatMap(tuple -> match(pool, tuple.getT1(), tuple.getT2(), userQuery,
                customPrompt, aliasGroups, limit));
    }

    private Mono<List<Post>> match(List<Post> pool, Map<String, TopicLabel> tags,
                                   Map<String, TopicLabel> categories, String userQuery,
                                   String customPrompt, List<List<String>> aliasGroups, int limit) {
        String normalizedQuery = userQuery == null ? "" : userQuery.toLowerCase(Locale.ROOT);
        Set<Integer> metadataMatches = new LinkedHashSet<>();
        StringBuilder articleList = new StringBuilder();

        for (int i = 0; i < pool.size(); i++) {
            Post post = pool.get(i);
            List<TopicLabel> postTags = labels(post.getSpec() != null
                ? post.getSpec().getTags() : null, tags);
            List<TopicLabel> postCategories = labels(post.getSpec() != null
                ? post.getSpec().getCategories() : null, categories);
            if (containsAnyLabel(normalizedQuery, postTags)
                || containsAnyLabel(normalizedQuery, postCategories)
                || matchesAliasGroup(normalizedQuery, postTags, postCategories, aliasGroups)) {
                metadataMatches.add(i + 1);
            }

            String title = post.getSpec() != null && post.getSpec().getTitle() != null
                ? post.getSpec().getTitle() : "";
            String excerpt = post.getSpec() != null && post.getSpec().getExcerpt() != null
                ? post.getSpec().getExcerpt().getRaw() : "";
            articleList.append(i + 1)
                .append(". 标题: ").append(clean(title, 160))
                .append(" | 标签: ").append(labelText(postTags))
                .append(" | 分类: ").append(labelText(postCategories))
                .append(" | 摘要: ").append(clean(excerpt, 100))
                .append("\n");
        }

        return aiProperties.getModelConfig()
            .flatMap(modelConfig -> {
                if (modelConfig.getChatApiKey() == null
                    || modelConfig.getChatApiKey().isBlank()) {
                    return Mono.just(select(pool, metadataMatches, limit));
                }
                String systemPrompt = """
                    你是博客文章主题分类器。用户问题可能同时含有“最新”、“文章”等结构词，
                    请识别其真正主题，综合文章的标题、摘要、标签和分类判断相关性。
                    标签和分类是强证据：即使标题没有出现主题词，只要标签/分类明确相关也应保留。
                    只返回 JSON：{"related":[1,3,5]}，不要解释或返回其他字段。
                    判断指令：""" + customPrompt;
                String prompt = "用户问题：" + (userQuery == null ? "" : userQuery)
                    + "\n\n文章列表：\n" + articleList;
                Map<String, Object> responseFormat = new LinkedHashMap<>();
                responseFormat.put("type", "json_object");
                return llmClient.chat(
                        modelConfig.getChatBaseUrl(), modelConfig.getChatApiKey(),
                        modelConfig.getChatModel(),
                        List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", prompt)),
                        0.0f, 768, responseFormat, null, UsageScenario.INTENT_PIPELINE)
                    .timeout(TIMEOUT)
                    .map(answer -> {
                        Set<Integer> union = new LinkedHashSet<>(metadataMatches);
                        union.addAll(LlmTitleFilterProcessor.parseRelatedIndices(answer));
                        List<Post> result = select(pool, union, limit);
                        log.debug("[TopicMatch] {} -> {} 条 (metadata={}, union={}, query='{}')",
                            pool.size(), result.size(), metadataMatches, union, userQuery);
                        return result;
                    })
                    .onErrorResume(error -> {
                        log.warn("[TopicMatch] LLM 判断失败，仅使用标签/分类强匹配: {}",
                            error.getMessage());
                        return Mono.just(select(pool, metadataMatches, limit));
                    });
            });
    }

    private static List<Post> select(List<Post> pool, Set<Integer> indices, int limit) {
        List<Post> result = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (indices.contains(i + 1)) {
                result.add(pool.get(i));
            }
        }
        return limit > 0 && result.size() > limit
            ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    private static List<TopicLabel> labels(List<String> names,
                                           Map<String, TopicLabel> lookup) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream().map(lookup::get).filter(java.util.Objects::nonNull).toList();
    }

    private static boolean containsAnyLabel(String query, List<TopicLabel> labels) {
        return labels.stream().anyMatch(label -> containsLabel(query, label.displayName())
            || containsLabel(query, label.slug()));
    }

    private static boolean containsLabel(String query, String label) {
        return label != null && label.length() >= 2
            && query.contains(label.toLowerCase(Locale.ROOT));
    }

    private static List<List<String>> parseAliases(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<List<String>> groups = new ArrayList<>();
        for (String group : configured.split("[;\uff1b]+")) {
            List<String> terms = java.util.Arrays.stream(group.split("[=|,\uff0c]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .map(term -> term.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
            if (terms.size() >= 2) {
                groups.add(terms);
            }
        }
        return groups;
    }

    private static boolean matchesAliasGroup(String query, List<TopicLabel> tags,
                                             List<TopicLabel> categories,
                                             List<List<String>> aliasGroups) {
        List<String> labels = new ArrayList<>();
        tags.forEach(label -> {
            labels.add(label.displayName());
            labels.add(label.slug());
        });
        categories.forEach(label -> {
            labels.add(label.displayName());
            labels.add(label.slug());
        });
        return aliasGroups.stream().anyMatch(group ->
            group.stream().anyMatch(query::contains)
                && group.stream().anyMatch(term -> labels.stream().anyMatch(label ->
                    label != null && label.toLowerCase(Locale.ROOT).contains(term))));
    }

    private static String labelText(List<TopicLabel> labels) {
        String text = labels.stream().map(TopicLabel::displayName)
            .filter(value -> value != null && !value.isBlank())
            .distinct().reduce((left, right) -> left + ", " + right).orElse("");
        return text.isBlank() ? "无" : clean(text, 160);
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > maxLength
            ? normalized.substring(0, maxLength) + "..." : normalized;
    }

    private record TopicLabel(String displayName, String slug) {}
}

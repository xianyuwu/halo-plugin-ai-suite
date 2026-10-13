package cn.rainwu.halo.ai.suite.service;

import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/** Generates tag suggestions without changing a post, then applies selected names on request. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TagGenerationService {
    private static final int MAX_CONTENT_LENGTH = 6000;
    private static final int MAX_TAGS = 8;
    // Some reasoning models consume their output budget before emitting the JSON result.
    private static final int MAX_RESPONSE_TOKENS = 2048;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveExtensionClient extensionClient;
    private final PostContentService postContentService;
    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    public record SuggestedTag(String name, boolean existing) {}

    public record GenerationResult(List<SuggestedTag> tags, List<String> currentTags) {}

    public record ApplyResult(List<String> tags, int added, List<Tag> tagResources) {}

    public Mono<GenerationResult> generate(String postName, int requestedCount) {
        int count = Math.max(1, Math.min(requestedCount, MAX_TAGS));
        return Mono.zip(
            extensionClient.fetch(Post.class, postName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("文章不存在"))),
            extensionClient.list(Tag.class, tag -> true, null).collectList(),
            aiProperties.getModelConfig()
        ).flatMap(tuple -> {
            Post post = tuple.getT1();
            List<Tag> allTags = tuple.getT2();
            var modelConfig = tuple.getT3();
            if (post.isDeleted() || post.getSpec() == null) {
                return Mono.error(new IllegalArgumentException("文章不可用"));
            }
            Map<String, Tag> byName = indexTags(allTags);
            List<String> current = tagDisplayNames(post, allTags);
            Set<String> currentKeys = new LinkedHashSet<>();
            current.stream().filter(name -> name != null && !name.isBlank())
                .map(TagGenerationService::key).forEach(currentKeys::add);
            String title = post.getSpec().getTitle() == null ? "" : post.getSpec().getTitle();
            String model = modelConfig.getEffectiveTagModel();
            return postContentService.getHeadContent(postName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("文章尚无可用内容")))
                .flatMap(content -> {
                    String raw = content.getRaw();
                    if (raw == null || raw.isBlank()) raw = content.getContent();
                    String plain = plainText(raw);
                    if (plain.isBlank()) {
                        return Mono.error(new IllegalArgumentException("文章正文为空，请先保存正文"));
                    }
                    String input = plain.length() > MAX_CONTENT_LENGTH
                        ? plain.substring(0, MAX_CONTENT_LENGTH) : plain;
                    String existingNames = allTags.stream()
                        .filter(tag -> tag.getSpec() != null && tag.getSpec().getDisplayName() != null)
                        .map(tag -> tag.getSpec().getDisplayName())
                        .filter(name -> !name.isBlank())
                        .distinct().limit(150)
                        .reduce((a, b) -> a + "、" + b).orElse("无");
                    String currentNames = current.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .reduce((a, b) -> a + "、" + b).orElse("无");
                    String prompt = "请为文章推荐 " + count + " 个尚未添加的准确、简短标签。"
                        + "优先复用站内已有标签，只有完全不匹配时才提出新标签。"
                        + "不要重复文章已有标签，也不要用过于宽泛的词。"
                        + "只返回 JSON，格式为 {\"tags\":[\"标签1\",\"标签2\"]}。\n"
                        + "站内已有标签：" + existingNames + "\n文章已有标签：" + currentNames
                        + "\n文章标题：" + title + "\n文章正文：" + input;
                    return llmClient.chatInternal(model,
                            List.of(Map.of("role", "user", "content", prompt)),
                            0.2f, MAX_RESPONSE_TOKENS, null,
                            UsageScenario.TAG_GENERATE, "disabled")
                        .map(rawOutput -> {
                            List<SuggestedTag> suggestions = parseSuggestions(rawOutput, MAX_TAGS, byName)
                                .stream().filter(tag -> !currentKeys.contains(key(tag.name())))
                                .limit(count).toList();
                            if (suggestions.isEmpty()) {
                                throw new IllegalArgumentException("模型未返回可用标签，请重试或调整文章内容");
                            }
                            return new GenerationResult(suggestions, current);
                        });
                });
        });
    }

    public Mono<ApplyResult> apply(String postName, List<String> selectedNames) {
        if (selectedNames == null || selectedNames.isEmpty() || selectedNames.size() > MAX_TAGS) {
            return Mono.error(new IllegalArgumentException("请选择 1–8 个标签"));
        }
        List<String> selected = normalizeNames(selectedNames, MAX_TAGS);
        if (selected.isEmpty() || selected.size() != selectedNames.size()) {
            return Mono.error(new IllegalArgumentException("标签名称无效或重复"));
        }
        return Mono.defer(() -> {
            List<Tag> createdTags = new ArrayList<>();
            return extensionClient.fetch(Post.class, postName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("文章不存在")))
                .flatMap(post -> {
                    if (post.isDeleted() || post.getSpec() == null) {
                        return Mono.error(new IllegalArgumentException("文章不可用"));
                    }
                    return extensionClient.list(Tag.class, tag -> true, null).collectList()
                        .flatMap(tags -> {
                            Map<String, Tag> existing = indexTags(tags);
                            return Flux.fromIterable(selected)
                                .concatMap(name -> {
                                    Tag match = existing.get(key(name));
                                    if (match != null) return Mono.just(match);
                                    Tag tag = new Tag();
                                    Metadata metadata = new Metadata();
                                    metadata.setGenerateName("tag-");
                                    tag.setMetadata(metadata);
                                    Tag.TagSpec spec = new Tag.TagSpec();
                                    spec.setDisplayName(name);
                                    spec.setSlug("ai-" + UUID.randomUUID().toString().substring(0, 12));
                                    tag.setSpec(spec);
                                    return extensionClient.create(tag).doOnNext(createdTags::add);
                                })
                                .collectList()
                                .flatMap(selectedTags -> extensionClient.fetch(Post.class, postName)
                                    .switchIfEmpty(Mono.error(new IllegalArgumentException("文章不存在")))
                                    .flatMap(latest -> {
                                        List<String> names = selectedTags.stream()
                                            .map(tag -> tag.getMetadata().getName()).toList();
                                        List<String> current = latest.getSpec().getTags();
                                        int before = current == null ? 0 : new LinkedHashSet<>(current).size();
                                        List<String> merged = mergeTagIds(current, names);
                                        Map<String, Tag> byId = new LinkedHashMap<>();
                                        tags.forEach(tag -> byId.put(tag.getMetadata().getName(), tag));
                                        selectedTags.forEach(tag -> byId.put(tag.getMetadata().getName(), tag));
                                        List<Tag> tagResources = merged.stream()
                                            .map(byId::get).filter(java.util.Objects::nonNull).toList();
                                        latest.getSpec().setTags(merged);
                                        return extensionClient.update(latest)
                                            .map(updated -> new ApplyResult(
                                                tagDisplayNames(updated, tags, selected, names),
                                                merged.size() - before, tagResources));
                                    }));
                        });
                })
                .onErrorResume(error -> cleanupCreatedTags(createdTags)
                    .then(Mono.error(error)));
        });
    }

    /** Only remove tags created by this apply call and still unused after a failure. */
    private Mono<Void> cleanupCreatedTags(List<Tag> createdTags) {
        return Flux.fromIterable(createdTags)
            .concatMap(tag -> {
                String tagId = tag.getMetadata().getName();
                return extensionClient.list(Post.class,
                        post -> post.getSpec() != null && post.getSpec().getTags() != null
                            && post.getSpec().getTags().contains(tagId), null)
                    .hasElements()
                    .flatMap(referenced -> referenced
                        ? Mono.<Void>empty() : extensionClient.delete(tag).then())
                    .onErrorResume(cleanupError -> {
                        log.warn("清理未关联的 AI 标签失败: tag={}", tagId, cleanupError);
                        return Mono.empty();
                    });
            })
            .then();
    }

    private static Map<String, Tag> indexTags(List<Tag> tags) {
        Map<String, Tag> index = new LinkedHashMap<>();
        for (Tag tag : tags) {
            if (tag.getSpec() == null || tag.getSpec().getDisplayName() == null) continue;
            index.putIfAbsent(key(tag.getSpec().getDisplayName()), tag);
        }
        return index;
    }

    static List<String> mergeTagIds(List<String> current, List<String> added) {
        Set<String> merged = new LinkedHashSet<>();
        if (current != null) merged.addAll(current);
        merged.addAll(added);
        return new ArrayList<>(merged);
    }

    private static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeNames(List<String> input, int limit) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String item : input) {
            if (item == null) continue;
            String name = item.trim().replaceAll("^[#＃]+", "").trim();
            if (name.isBlank() || name.length() > 24 || name.contains("\n")
                || name.contains("\r") || name.contains("<") || name.contains(">")) continue;
            if (keys.add(key(name))) result.add(name);
            if (result.size() >= limit) break;
        }
        return result;
    }

    static List<SuggestedTag> parseSuggestions(String raw, int limit, Map<String, Tag> existing) {
        List<String> names = new ArrayList<>();
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = raw.trim().replaceFirst("(?s)^```(?:json)?\\s*", "")
            .replaceFirst("(?s)\\s*```$", "").trim();
        try {
            JsonNode root = MAPPER.readTree(cleaned);
            JsonNode array = root.isArray() ? root : root.path("tags");
            if (array.isArray()) {
                array.forEach(node -> { if (node.isTextual()) names.add(node.asText()); });
            }
        } catch (Exception ignored) {
            // Do not turn malformed JSON fragments into tag names.
            if (cleaned.startsWith("{") || cleaned.startsWith("[")) return List.of();
            for (String part : cleaned.split("[,，、\\n]")) {
                names.add(part.replaceFirst("^\\s*(?:[-*•]|\\d+[.)、])\\s*", ""));
            }
        }
        return normalizeNames(names, limit).stream()
            .map(name -> {
                Tag match = existing.get(key(name));
                return new SuggestedTag(match == null ? name : match.getSpec().getDisplayName(),
                    match != null);
            }).toList();
    }

    private static List<String> tagDisplayNames(Post post, List<Tag> tags) {
        return tagDisplayNames(post, tags, List.of(), List.of());
    }

    private static List<String> tagDisplayNames(Post post, List<Tag> tags,
                                                List<String> selected, List<String> selectedIds) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (Tag tag : tags) {
            if (tag.getSpec() != null) {
                byId.put(tag.getMetadata().getName(), tag.getSpec().getDisplayName());
            }
        }
        for (int i = 0; i < selectedIds.size(); i++) {
            byId.putIfAbsent(selectedIds.get(i), selected.get(i));
        }
        if (post.getSpec().getTags() == null) return List.of();
        return post.getSpec().getTags().stream()
            .map(id -> byId.getOrDefault(id, id)).toList();
    }

    private static String plainText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?s)<[^>]+>", " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replaceAll("\\s+", " ").trim();
    }
}

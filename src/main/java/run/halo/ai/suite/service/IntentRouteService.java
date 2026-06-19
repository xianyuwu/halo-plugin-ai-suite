package run.halo.ai.suite.service;

import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.ai.suite.extension.IntentRoute;
import run.halo.ai.suite.extension.IntentRoute.PipelineStep;
import run.halo.ai.suite.extension.IntentRoute.Spec;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 意图路由服务 — 管理意图配置 CRUD + 内置意图懒注入.
 * <p>
 * 范式对齐 {@code EvaluationDatasetService}：CRUD 走 {@link ReactiveExtensionClient}，
 * 阻塞调用包在 {@code boundedElastic} 上；内置数据走 {@code switchIfEmpty + onErrorResume}
 * 的懒触发幂等注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouteService {

    // ===== 内置意图 ID（命名固定，便于升级时识别/更新）=====
    public static final String BUILTIN_LATEST_POSTS = "builtin-latest-posts";
    public static final String BUILTIN_HOT_ARTICLES = "builtin-hot-articles";
    public static final String BUILTIN_BY_TAG = "builtin-by-tag";
    public static final String BUILTIN_BY_CATEGORY = "builtin-by-category";

    private static final Set<String> PROCESSOR_TYPES = Set.of(
        "TOPIC_MATCH", "LLM_TITLE_FILTER", "TAG_MATCH", "KEYWORD_MATCH", "CATEGORY_MATCH",
        "TIME_SORT", "VISIT_SORT");
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,58}[a-z0-9])?");
    private static final Duration ENABLED_CACHE_TTL = Duration.ofSeconds(30);

    private final ReactiveExtensionClient client;
    private volatile Mono<List<IntentRoute>> enabledIntentsCache;

    // ===== 查询 =====

    /**
     * 列出所有意图（含内置），按 priority 降序 + name 升序排列.
     * <p>每次查询前触发一次内置意图的懒注入，保证框架首次启动即有数据可用。
     */
    public Mono<List<IntentRouteDto>> listIntents() {
        return ensureBuiltinIntents()
            .then(client.listAll(IntentRoute.class, ListOptions.builder().build(),
                    Sort.by(Sort.Order.desc("spec.priority"), Sort.Order.asc("metadata.name")))
                .map(this::toDto)
                .collectList());
    }

    public Mono<IntentRouteDto> getIntent(String id) {
        return client.fetch(IntentRoute.class, id).map(this::toDto);
    }

    /**
     * 列出所有启用的意图，按 priority 降序 — 供 {@code ChatIntent} 意图识别使用.
     */
    public Mono<List<IntentRoute>> listEnabledIntents() {
        Mono<List<IntentRoute>> cached = enabledIntentsCache;
        if (cached != null) {
            return cached;
        }
        Mono<List<IntentRoute>> created = ensureBuiltinIntents()
            .then(client.listAll(IntentRoute.class, ListOptions.builder().build(),
                    Sort.by(Sort.Order.desc("spec.priority"), Sort.Order.asc("metadata.name")))
                .filter(r -> r.getSpec() != null && Boolean.TRUE.equals(r.getSpec().getEnabled()))
                .collectList())
            .cache(ENABLED_CACHE_TTL);
        enabledIntentsCache = created;
        return created;
    }

    // ===== 增删改 =====

    public Mono<IntentRouteDto> saveIntent(SaveIntentRequest request) {
        validate(request);
        return Mono.fromCallable(() -> {
            String id = request.id() != null && !request.id().isBlank()
                ? request.id() : slugify(request.displayName());
            // 内置意图禁止改名（避免丢失升级识别），但允许改配置
            boolean builtin = BUILTIN_LATEST_POSTS.equals(id)
                || BUILTIN_HOT_ARTICLES.equals(id)
                || BUILTIN_BY_TAG.equals(id)
                || BUILTIN_BY_CATEGORY.equals(id);
            IntentRoute existing = client.fetch(IntentRoute.class, id).block();
            IntentRoute route = existing != null ? existing : new IntentRoute();
            if (existing == null) {
                Metadata md = new Metadata();
                md.setName(id);
                route.setMetadata(md);
            }
            route.setSpec(toSpec(request, builtin));
            IntentRoute saved = existing == null
                ? client.create(route).block()
                : client.update(route).block();
            return toDto(saved);
        }).subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess(ignored -> invalidateEnabledCache());
    }

    public Mono<Void> deleteIntent(String id) {
        if (BUILTIN_LATEST_POSTS.equals(id)
            || BUILTIN_HOT_ARTICLES.equals(id)
            || BUILTIN_BY_TAG.equals(id)
            || BUILTIN_BY_CATEGORY.equals(id)) {
            return Mono.error(new IllegalArgumentException("内置意图不能删除"));
        }
        return client.fetch(IntentRoute.class, id)
            .flatMap(client::delete)
            .then()
            .doOnSuccess(ignored -> invalidateEnabledCache());
    }

    // ===== 内置意图懒注入 =====

    /**
     * 内置意图懒触发注入 — 照抄 EvaluationDatasetService.ensureDefaultDataset 范式.
     * <p>幂等：每个内置意图 fetch 不存在则 create，已存在则跳过；
     * create 失败（如并发）再 fetch 一次兜底，避免 AlreadyExists 异常。
     */
    public Mono<Void> ensureBuiltinIntents() {
        return ensureOne(BUILTIN_LATEST_POSTS, "最新文章",
            "命中「最新/最近/new」等关键词时，按发布时间倒序列出最新文章。"
                + "默认 pipeline：TOPIC_MATCH（标签/分类 + LLM 综合匹配）→ TIME_SORT。",
            List.of("最新", "最近", "新文章", "近期", "new", "latest"),
            List.of(
                step("TOPIC_MATCH", "prompt", "综合标题、摘要、标签和分类判断主题相关性",
                    "aliases", "AI=人工智能"),
                step("TIME_SORT", "order", "desc", "limit", "10")
            ),
            "按发布时间从近到远列出文章，每篇标注发布日期（YYYY-MM-DD），编号列表格式。")
            .then(migrateLegacyLatestRoute())
            .then(ensureLatestTopicAliases())
            .then(ensureOne(BUILTIN_HOT_ARTICLES, "热门文章",
                "命中「热门/热文/hot」等关键词时，按浏览量倒序列出热门文章。"
                    + "迁移自原硬编码 HOT_ARTICLES 意图。",
                List.of("热门", "热文", "hot", "popular", "推荐文章", "推荐热门",
                    "热门文章", "热门推荐", "最受欢迎"),
                List.of(step("VISIT_SORT", "limit", "10")),
                "先写一句简短开场白，然后严格按浏览量从高到低，用 Markdown 编号列表 + "
                    + "[标题](链接) + 一句话点评（10-20 字）格式列出文章。"))
            .then(ensureOne(BUILTIN_BY_TAG, "按标签查",
                "命中「标签/tag」等关键词时，从用户问题中提取标签关键词，"
                    + "匹配 Post.spec.tags 后按发布时间倒序列出。",
                List.of("标签", "tag"),
                List.of(
                    step("TAG_MATCH", "mode", "from_query"),
                    step("TIME_SORT", "order", "desc", "limit", "10")
                ),
                "按发布时间倒序列出匹配到的文章，开头说明按哪个标签筛选到了几篇。"))
            .then(migrateLegacyTagRoute())
            .then(ensureOne(BUILTIN_BY_CATEGORY, "按分类查",
                "命中「分类/category」等关键词时，从用户问题中提取分类后按时间排序。",
                List.of("分类", "category"),
                List.of(
                    step("CATEGORY_MATCH", "mode", "from_query"),
                    step("TIME_SORT", "order", "desc", "limit", "10")
                ),
                "按发布时间倒序列出匹配到的文章，开头说明按哪个分类筛选到了几篇。"));
    }

    /** 将旧版未编辑的「仅标题 LLM」默认路由升级为主题综合匹配。 */
    private Mono<Void> migrateLegacyLatestRoute() {
        return client.fetch(IntentRoute.class, BUILTIN_LATEST_POSTS)
            .flatMap(route -> {
                Spec spec = route.getSpec();
                if (spec == null || spec.getPipeline() == null
                    || spec.getPipeline().size() != 2) {
                    return Mono.empty();
                }
                PipelineStep first = spec.getPipeline().get(0);
                PipelineStep second = spec.getPipeline().get(1);
                if (!"LLM_TITLE_FILTER".equals(first.getType())
                    || !"TIME_SORT".equals(second.getType())) {
                    return Mono.empty();
                }
                String prompt = first.getParams() != null
                    ? first.getParams().get("prompt") : null;
                if (!"判断标题是否与用户问题主题相关".equals(prompt)) {
                    return Mono.empty();
                }
                spec.setDescription("命中「最新/最近/new」等关键词时，按发布时间倒序列出最新文章。"
                    + "默认 pipeline：TOPIC_MATCH（标签/分类 + LLM 综合匹配）→ TIME_SORT。");
                first.setType("TOPIC_MATCH");
                first.getParams().put("prompt", "综合标题、摘要、标签和分类判断主题相关性");
                first.getParams().put("aliases", "AI=人工智能");
                spec.setUpdatedAt(Instant.now());
                return client.update(route).then();
            });
    }

    /** 为已迁移的内置路由补充默认 AI 主题别名，不覆盖管理员已配的别名。 */
    private Mono<Void> ensureLatestTopicAliases() {
        return client.fetch(IntentRoute.class, BUILTIN_LATEST_POSTS)
            .flatMap(route -> {
                Spec spec = route.getSpec();
                if (spec == null || spec.getPipeline() == null || spec.getPipeline().isEmpty()) {
                    return Mono.empty();
                }
                PipelineStep first = spec.getPipeline().get(0);
                if (!"TOPIC_MATCH".equals(first.getType()) || first.getParams() == null
                    || first.getParams().containsKey("aliases")) {
                    return Mono.empty();
                }
                first.getParams().put("aliases", "AI=人工智能");
                spec.setUpdatedAt(Instant.now());
                return client.update(route).then();
            });
    }

    /**
     * 只迁移旧版内置默认值；如果管理员已经编辑过该路由，不覆盖自定义配置。
     */
    private Mono<Void> migrateLegacyTagRoute() {
        return client.fetch(IntentRoute.class, BUILTIN_BY_TAG)
            .flatMap(route -> {
                Spec spec = route.getSpec();
                if (spec == null || spec.getTriggerPatterns() == null
                    || !spec.getTriggerPatterns().equals(
                        List.of("标签", "分类", "tag", "category"))
                    || spec.getPipeline() == null || spec.getPipeline().isEmpty()) {
                    return Mono.empty();
                }
                PipelineStep first = spec.getPipeline().get(0);
                String legacyMode = first.getParams() != null
                    ? first.getParams().get("mode") : null;
                if (!"TAG_MATCH".equals(first.getType())
                    || !"query_extract".equals(legacyMode)) {
                    return Mono.empty();
                }
                spec.setDescription("命中「标签/tag」等关键词时，从用户问题中提取标签关键词，"
                    + "匹配 Post.spec.tags 后按发布时间倒序列出。");
                spec.setTriggerPatterns(List.of("标签", "tag"));
                first.getParams().put("mode", "from_query");
                spec.setUpdatedAt(Instant.now());
                return client.update(route).then();
            });
    }

    private void invalidateEnabledCache() {
        enabledIntentsCache = null;
    }

    private void validate(SaveIntentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.displayName() == null || request.displayName().isBlank()
            || request.displayName().length() > 80) {
            throw new IllegalArgumentException("显示名称必填且不能超过 80 个字符");
        }
        if (request.id() != null && !request.id().isBlank()
            && !VALID_ID.matcher(request.id()).matches()) {
            throw new IllegalArgumentException("ID 只能包含小写字母、数字和中划线，最长 60 位");
        }
        if (request.priority() != null && (request.priority() < -1000 || request.priority() > 1000)) {
            throw new IllegalArgumentException("优先级必须在 -1000 到 1000 之间");
        }
        List<String> patterns = request.triggerPatterns() != null
            ? request.triggerPatterns() : List.of();
        if (patterns.size() > 20) {
            throw new IllegalArgumentException("触发正则最多 20 条");
        }
        for (String value : patterns) {
            if (value == null || value.isBlank() || value.length() > 200) {
                throw new IllegalArgumentException("触发正则不能为空且每条不能超过 200 个字符");
            }
            if (value.matches(".*\\([^)]*[+*][^)]*\\)[+*?{].*")
                || value.matches(".*\\\\[1-9].*")) {
                throw new IllegalArgumentException("触发正则不允许嵌套量词或反向引用");
            }
            try {
                Pattern.compile(value, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("无效的触发正则: " + value);
            }
        }
        List<PipelineStep> pipeline = request.pipeline() != null ? request.pipeline() : List.of();
        if (pipeline.isEmpty() || pipeline.size() > 12) {
            throw new IllegalArgumentException("Pipeline 需要 1-12 个处理步骤");
        }
        for (PipelineStep pipelineStep : pipeline) {
            if (pipelineStep == null || !PROCESSOR_TYPES.contains(pipelineStep.getType())) {
                throw new IllegalArgumentException("不支持的处理器: "
                    + (pipelineStep == null ? "null" : pipelineStep.getType()));
            }
            if (pipelineStep.getParams() != null && pipelineStep.getParams().size() > 20) {
                throw new IllegalArgumentException("单个处理器参数最多 20 项");
            }
            if (pipelineStep.getParams() != null) {
                pipelineStep.getParams().forEach((key, value) -> {
                    if (key == null || key.isBlank() || key.length() > 60
                        || value == null || value.length() > 2000) {
                        throw new IllegalArgumentException("处理器参数名最长 60，参数值最长 2000 个字符");
                    }
                });
            }
        }
        if (request.outputTemplate() != null && request.outputTemplate().length() > 4000) {
            throw new IllegalArgumentException("输出模板不能超过 4000 个字符");
        }
    }

    private Mono<Void> ensureOne(String id, String displayName, String description,
                                 List<String> triggerPatterns, List<PipelineStep> pipeline,
                                 String outputTemplate) {
        return client.fetch(IntentRoute.class, id)
            .switchIfEmpty(Mono.defer(() -> {
                IntentRoute route = new IntentRoute();
                Metadata md = new Metadata();
                md.setName(id);
                route.setMetadata(md);
                Spec spec = new Spec();
                spec.setDisplayName(displayName);
                spec.setDescription(description);
                spec.setEnabled(true);
                spec.setPriority(0);
                spec.setBuiltin(true);
                spec.setTriggerPatterns(triggerPatterns);
                spec.setLlmFallback(false);
                spec.setLlmFallbackHint("");
                spec.setPipeline(pipeline);
                spec.setOutputTemplate(outputTemplate);
                spec.setUpdatedAt(Instant.now());
                route.setSpec(spec);
                return client.create(route)
                    .doOnError(e -> log.warn("[IntentRoute] 创建内置意图 {} 失败: {}", id, e.getMessage()))
                    .onErrorResume(e -> client.fetch(IntentRoute.class, id));
            }))
            .then();
    }

    private static PipelineStep step(String type, String... kv) {
        PipelineStep s = new PipelineStep();
        s.setType(type);
        s.setParams(new java.util.LinkedHashMap<>());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s.getParams().put(kv[i], kv[i + 1]);
        }
        return s;
    }

    // ===== 转换 =====

    private Spec toSpec(SaveIntentRequest req, boolean builtin) {
        Spec spec = new Spec();
        spec.setDisplayName(req.displayName() != null && !req.displayName().isBlank()
            ? req.displayName() : "未命名意图");
        spec.setDescription(req.description() != null ? req.description() : "");
        spec.setEnabled(req.enabled() != null ? req.enabled() : Boolean.TRUE);
        spec.setPriority(req.priority() != null ? req.priority() : 0);
        spec.setBuiltin(builtin);
        spec.setTriggerPatterns(req.triggerPatterns() != null ? req.triggerPatterns() : List.of());
        spec.setLlmFallback(req.llmFallback() != null ? req.llmFallback() : Boolean.FALSE);
        spec.setLlmFallbackHint(req.llmFallbackHint() != null ? req.llmFallbackHint() : "");
        spec.setPipeline(req.pipeline() != null ? req.pipeline() : List.of());
        spec.setOutputTemplate(req.outputTemplate() != null ? req.outputTemplate() : "");
        spec.setUpdatedAt(Instant.now());
        return spec;
    }

    private IntentRouteDto toDto(IntentRoute route) {
        Spec spec = route.getSpec();
        return new IntentRouteDto(
            route.getMetadata() != null ? route.getMetadata().getName() : "",
            spec != null ? spec.getDisplayName() : "",
            spec != null ? spec.getDescription() : "",
            spec != null && Boolean.TRUE.equals(spec.getEnabled()),
            spec != null && spec.getPriority() != null ? spec.getPriority() : 0,
            spec != null && Boolean.TRUE.equals(spec.getBuiltin()),
            spec != null && spec.getTriggerPatterns() != null ? spec.getTriggerPatterns() : List.of(),
            spec != null && Boolean.TRUE.equals(spec.getLlmFallback()),
            spec != null ? spec.getLlmFallbackHint() : "",
            spec != null && spec.getPipeline() != null ? spec.getPipeline() : List.of(),
            spec != null ? spec.getOutputTemplate() : "",
            spec != null && spec.getUpdatedAt() != null ? spec.getUpdatedAt().toString() : ""
        );
    }

    private String slugify(String name) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "intent-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        return base;
    }

    // ===== DTO =====

    public record SaveIntentRequest(
        String id,
        String displayName,
        String description,
        Boolean enabled,
        Integer priority,
        List<String> triggerPatterns,
        Boolean llmFallback,
        String llmFallbackHint,
        List<PipelineStep> pipeline,
        String outputTemplate
    ) {}

    public record IntentRouteDto(
        String id,
        String displayName,
        String description,
        boolean enabled,
        int priority,
        boolean builtin,
        List<String> triggerPatterns,
        boolean llmFallback,
        String llmFallbackHint,
        List<PipelineStep> pipeline,
        String outputTemplate,
        String updatedAt
    ) {}

    /**
     * 按 priority 降序比较器 — 供 ChatIntent 用，避免再走一次 DB Sort.
     */
    public static final Comparator<IntentRoute> BY_PRIORITY_DESC =
        Comparator.<IntentRoute, Integer>comparing(
            r -> r.getSpec() != null && r.getSpec().getPriority() != null
                ? r.getSpec().getPriority() : 0,
            Comparator.reverseOrder())
            .thenComparing(r -> r.getMetadata() != null ? r.getMetadata().getName() : "");

    /** 收集启用意图列表时用的字段格式化（调试用）. */
    public static List<String> enabledNames(List<IntentRoute> routes) {
        return routes.stream()
            .map(r -> r.getSpec() != null ? r.getSpec().getDisplayName() : "?")
            .collect(Collectors.toList());
    }
}

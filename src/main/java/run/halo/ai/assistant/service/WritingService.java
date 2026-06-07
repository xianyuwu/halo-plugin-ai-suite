package run.halo.ai.assistant.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.llm.LlmClient;

/**
 * AI 写作辅助 — Halo 文章编辑器内嵌的续写、润色、扩写、简化、摘要、翻译能力。
 *
 * <p>入口：{@code ConsoleWritingEndpoint}。输出流式事件（{@link AssistEvent}），
 * endpoint 映射为 SSE 协议（{@code data: <token>} / {@code data: [DONE]} /
 * {@code event: error\ndata: <msg>}）。
 *
 * <h3>Prompt 策略</h3>
 * 每个 action 都有独立的温度与指令，避免一刀切：
 * <ul>
 *   <li>polish / simplify / summarize / translate-en：温度 0.4（精确任务）</li>
 *   <li>expand：温度 0.6（中等创造性）</li>
 *   <li>continue：温度 0.7（最高创造性，续写需要发挥）</li>
 * </ul>
 *
 * <h3>输入限制</h3>
 * 原文超过 {@value #MAX_INPUT_LENGTH} 字符直接返回错误，不静默截断 —
 * 静默截断会让 LLM 收到部分原文，给出莫名其妙的处理结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WritingService {

    /** 单次输入字符上限 */
    static final int MAX_INPUT_LENGTH = 6000;

    /** Per-action 温度表（缺省回退到 ChatConfig 温度） */
    private static final Map<String, Float> ACTION_TEMPERATURE = Map.of(
        "polish", 0.4f,
        "simplify", 0.4f,
        "summarize", 0.4f,
        "translate-en", 0.4f,
        "expand", 0.6f,
        "continue", 0.7f,
        "outline", 0.3f
    );

    /** 统一 system 提示词 — 作为"全局规则"约束所有 action */
    private static final String SYSTEM_PROMPT = """
        你是一名专业的中文写作助手。所有任务的目标是「严格按用户指令处理给定文本」。
        规则：
        1. 不要修改原文的事实、数字、专有名词、人名、链接；
        2. 输出中不要加「以下是…」「润色后：」「摘要：」等前缀或解释；
        3. 直接输出最终结果，不要包裹引号或代码块。
        """;

    private static final Map<String, ActionSpec> ACTION_SPECS = Map.ofEntries(
        Map.entry("polish", new ActionSpec(
            "润色",
            "润色以下文本，使其表达更流畅、有文采。",
            "保留全部事实、数字、专有名词；保留原文长度。",
            "与原文接近"
        )),
        Map.entry("continue", new ActionSpec(
            "续写",
            "从末尾自然衔接，延续原文的语态、视角、术语、段落风格。",
            "只输出续写部分（不含原文），不要重复原文最后一句。",
            "约 100-300 字"
        )),
        Map.entry("expand", new ActionSpec(
            "扩写",
            "扩写以下文本，补充背景、论据、举例。",
            "保持原文骨架不变；不引入原文没有的具体数字或人名。",
            "约为原文 1.5-2 倍"
        )),
        Map.entry("simplify", new ActionSpec(
            "简化",
            "用通俗易懂的语言重新表达。目标受众：普通读者。",
            "删除冗余，保留核心信息。",
            "比原文短 30-50%"
        )),
        Map.entry("summarize", new ActionSpec(
            "摘要",
            "提炼核心信息，生成 1-3 句话的中文摘要。",
            "使用陈述句，涵盖主要观点。",
            "不超过 150 字"
        )),
        Map.entry("translate-en", new ActionSpec(
            "译英",
            "翻译以下文本为自然英文。",
            "保持专业术语一致；语气符合英文技术博客风格。",
            "与原文接近"
        )),
        Map.entry("outline", new ActionSpec(
            "生成大纲",
            "根据用户提供的主题，生成结构化的文章大纲。",
            "严格输出合法 JSON（不要输出任何其他文字），格式如下：\n"
                + "{\"title\": \"文章主标题\", \"summary\": \"一句话概述文章目标和范围\", "
                + "\"sections\": [{\"heading\": \"一、章节名\", \"summary\": \"1-2句话概括本节核心内容\"}]}\n"
                + "要求：sections 包含 5-8 个元素；每个 heading 和 summary 都不能为空；"
                + "符合中文技术博客的常见结构。",
            "5-8 个二级标题"
        ))
    );

    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    /**
     * 流式 AI 写作辅助。返回 {@link AssistEvent} 流，endpoint 映射为 SSE。
     *
     * <p>正常结束：连续输出 N 个 {@link AssistEvent.Token} + 1 个 {@link AssistEvent.Done}。
     * 异常结束：输出 1 个 {@link AssistEvent.Error}。
     */
    public Flux<AssistEvent> assistStream(String text, String action, String instruction) {
        ValidationResult v = validate(text, action);
        if (!v.ok()) {
            return Flux.just(AssistEvent.error(v.message()), AssistEvent.done());
        }
        String act = v.action();

        return Mono.zip(
            aiProperties.getModelConfig().switchIfEmpty(Mono.error(new IllegalStateException("模型未配置"))),
            aiProperties.getChatConfig().defaultIfEmpty(new AIProperties.ChatConfig()),
            aiProperties.getWritingConfig().defaultIfEmpty(new AIProperties.WritingConfig())
        ).flatMapMany(tuple -> {
            AIProperties.ModelConfig modelCfg = tuple.getT1();
            AIProperties.ChatConfig chatCfg = tuple.getT2();
            AIProperties.WritingConfig writingCfg = tuple.getT3();

            // 独立模型：writingBaseUrl 非空时用它，否则回退到对话模型
            String baseUrl = isNotBlank(writingCfg.getWritingBaseUrl()) ? writingCfg.getWritingBaseUrl() : modelCfg.getChatBaseUrl();
            String apiKey = isNotBlank(writingCfg.getWritingApiKey()) ? writingCfg.getWritingApiKey() : modelCfg.getChatApiKey();
            String model = isNotBlank(writingCfg.getWritingModel()) ? writingCfg.getWritingModel() : modelCfg.getChatModel();

            // 大纲温度从配置读，其他 action 用硬编码温度表
            float temperature;
            if ("outline".equals(act)) {
                temperature = writingCfg.getOutlineTemperature() > 0 ? writingCfg.getOutlineTemperature() : 0.3f;
            } else {
                temperature = ACTION_TEMPERATURE.getOrDefault(act, chatCfg.getTemperature());
            }
            int maxTokens = writingCfg.getMaxTokens() > 0 ? writingCfg.getMaxTokens() : 2048;

            // 大纲 action 动态生成 constraints（章节数 + 自定义要求）
            List<Map<String, String>> messages = buildMessages(act, instruction, text, writingCfg);

            Map<String, Object> responseFormat = "outline".equals(act)
                ? Map.of("type", "json_object")
                : null;

            return llmClient.chatStreamInternal(baseUrl, apiKey, model, messages, temperature, maxTokens, responseFormat)
                .map(AssistEvent::token)
                .concatWith(Flux.just(AssistEvent.done()))
                .onErrorResume(e -> {
                    log.warn("[WritingService] LLM 调用失败: {}", e.getMessage());
                    return Flux.just(AssistEvent.error("AI 调用失败: " + e.getMessage()), AssistEvent.done());
                });
        });
    }

    /**
     * 非流式 — 仅在流式解析失败时作为 fallback。返回纯文本结果或错误信息。
     */
    public Mono<String> assist(String text, String action, String instruction) {
        ValidationResult v = validate(text, action);
        if (!v.ok()) {
            return Mono.just(v.message());
        }
        String act = v.action();

        return Mono.zip(
            aiProperties.getModelConfig().switchIfEmpty(Mono.error(new IllegalStateException("模型未配置"))),
            aiProperties.getChatConfig().defaultIfEmpty(new AIProperties.ChatConfig()),
            aiProperties.getWritingConfig().defaultIfEmpty(new AIProperties.WritingConfig())
        ).flatMap(tuple -> {
            AIProperties.ModelConfig modelCfg = tuple.getT1();
            AIProperties.ChatConfig chatCfg = tuple.getT2();
            AIProperties.WritingConfig writingCfg = tuple.getT3();

            String baseUrl = isNotBlank(writingCfg.getWritingBaseUrl()) ? writingCfg.getWritingBaseUrl() : modelCfg.getChatBaseUrl();
            String apiKey = isNotBlank(writingCfg.getWritingApiKey()) ? writingCfg.getWritingApiKey() : modelCfg.getChatApiKey();
            String model = isNotBlank(writingCfg.getWritingModel()) ? writingCfg.getWritingModel() : modelCfg.getChatModel();

            float temperature;
            if ("outline".equals(act)) {
                temperature = writingCfg.getOutlineTemperature() > 0 ? writingCfg.getOutlineTemperature() : 0.3f;
            } else {
                temperature = ACTION_TEMPERATURE.getOrDefault(act, chatCfg.getTemperature());
            }
            int maxTokens = writingCfg.getMaxTokens() > 0 ? writingCfg.getMaxTokens() : 2048;
            List<Map<String, String>> messages = buildMessages(act, instruction, text, writingCfg);

            Map<String, Object> responseFormat = "outline".equals(act)
                ? Map.of("type", "json_object")
                : null;

            return llmClient.chatInternal(baseUrl, apiKey, model, messages, temperature, maxTokens, responseFormat);
        })
        .onErrorResume(e -> {
            log.warn("[WritingService] LLM 调用失败: {}", e.getMessage());
            return Mono.just("AI 调用失败: " + e.getMessage());
        });
    }

    // ===== 内部工具方法 =====

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private record ValidationResult(boolean ok, String action, String message) {
        static ValidationResult ok(String action) { return new ValidationResult(true, action, null); }
        static ValidationResult err(String msg) { return new ValidationResult(false, null, msg); }
    }

    private ValidationResult validate(String text, String action) {
        if (text == null || text.isBlank()) {
            return ValidationResult.err("请先选择要处理的文本");
        }
        // maxInputLength 从 WritingConfig 读，这里用默认值（实际限制在调用方判断）
        if (text.length() > MAX_INPUT_LENGTH) {
            return ValidationResult.err("原文超过 " + MAX_INPUT_LENGTH
                + " 字符限制（当前 " + text.length() + "），请先精简或分段处理");
        }
        String act = (action == null || action.isBlank()) ? "polish" : action.trim();
        if (!ACTION_SPECS.containsKey(act)) {
            return ValidationResult.err("不支持的操作类型: " + act);
        }
        return ValidationResult.ok(act);
    }

    private List<Map<String, String>> buildMessages(String action, String instruction, String text,
                                                      AIProperties.WritingConfig writingCfg) {
        ActionSpec spec = ACTION_SPECS.get(action);

        // 大纲 action 动态生成 constraints（章节数 + 嵌套深度 + 编号方式 + 自定义要求）
        if ("outline".equals(action)) {
            int sections = writingCfg.getOutlineSections() > 0 ? writingCfg.getOutlineSections() : 6;
            int depth = writingCfg.getOutlineDepth() >= 1 && writingCfg.getOutlineDepth() <= 3
                ? writingCfg.getOutlineDepth() : 1;
            String numbering = writingCfg.getOutlineNumbering();
            if (numbering == null || numbering.isBlank()) numbering = "chinese";

            // 顶层 heading 示例：按 numbering 切换（一级永远用全局编号）
            String topHeadingExample = switch (numbering) {
                case "chinese-paren" -> "（一）章节名";
                case "arabic" -> "1. 章节名";
                case "roman" -> "I. 章节名";
                case "none" -> "章节名";
                default -> "一、章节名";
            };
            // 顶层编号说明（写到 constraints 里给 LLM 看）
            String numberingHint = switch (numbering) {
                case "chinese-paren" -> "顶层 heading 必须以\"（一）（二）（三）\"格式的中文括号数字编号开头";
                case "arabic" -> "顶层 heading 必须以\"1. 2. 3.\"格式的阿拉伯数字编号开头";
                case "roman" -> "顶层 heading 必须以\"I. II. III.\"格式的罗马数字编号开头";
                case "none" -> "顶层 heading 不要加任何编号前缀，直接写章节名";
                default -> "顶层 heading 必须以\"一、二、三、\"格式的中文数字编号开头";
            };

            // 动态拼 JSON 示例：按 depth 拼出对应嵌套层级
            // depth=1: 扁平 sections
            // depth=2: sections[*].children（1 个示例 child 即可，LLM 知道要照结构生成 N 个）
            // depth=3: sections[*].children[*].children
            // 子级 heading 统一用阿拉伯数字（"1.1" / "1.1.1"），让 LLM 输出最稳
            String sectionJson;
            if (depth == 1) {
                sectionJson = "{\"heading\": \"" + topHeadingExample + "\", \"summary\": \"1-2句话概括本节核心内容\"}";
            } else if (depth == 2) {
                sectionJson = "{\"heading\": \"" + topHeadingExample + "\", "
                    + "\"summary\": \"1-2句话概括本节核心内容\", "
                    + "\"children\": ["
                    + "{\"heading\": \"1.1 子章节名\", \"summary\": \"1-2句话概括本子节核心内容\"}"
                    + "]}";
            } else {
                // depth=3
                sectionJson = "{\"heading\": \"" + topHeadingExample + "\", "
                    + "\"summary\": \"1-2句话概括本节核心内容\", "
                    + "\"children\": ["
                    + "{\"heading\": \"1.1 子章节名\", \"summary\": \"1-2句话\", "
                    + "\"children\": ["
                    + "{\"heading\": \"1.1.1 子子章节名\", \"summary\": \"1-2句话\"}"
                    + "]}"
                    + "]}";
            }

            // 深度说明（按 depth 给出每层结构要求）
            String depthHint = depth == 1
                ? "只生成 1 层结构（顶层 sections）"
                : (depth == 2
                    ? "生成 2 层结构：顶层 sections 包含 " + sections + " 个元素，"
                        + "每个顶层元素必须有 children 字段，包含 1-3 个子章节"
                    : "生成 3 层结构：顶层 sections 包含 " + sections + " 个元素，"
                        + "每个顶层元素必须有 children（1-3 个），"
                        + "每个子章节必须有 children（1-2 个孙章节）");

            // 子级编号约束（depth>1 时才有意义）
            String subHeadingHint = depth == 1
                ? ""
                : "子级 heading（深度 2/3）必须用阿拉伯数字编号\"1.1 / 1.1.1\"格式，从顶层编号派生";

            String dynamicConstraints = "严格输出合法 JSON（不要输出任何其他文字），格式如下：\n"
                + "{\"title\": \"文章主标题\", \"depth\": " + depth + ", "
                + "\"summary\": \"一句话概述文章目标和范围\", "
                + "\"sections\": [" + sectionJson + "]}\n"
                + "要求："
                + depthHint + "；"
                + "每个 heading 和 summary 都不能为空；"
                + numberingHint + "；"
                + subHeadingHint + "；"
                + "符合中文技术博客的常见结构。";
            String extraPrompt = writingCfg.getOutlineExtraPrompt();
            if (isNotBlank(extraPrompt)) {
                dynamicConstraints += "\n附加要求：" + extraPrompt;
            }
            spec = new ActionSpec(spec.label(), spec.task(), dynamicConstraints,
                sections + " 个顶层章节" + (depth > 1 ? "（共 " + depth + " 层嵌套）" : ""));
        }

        String userContent = buildUserContent(spec, instruction, text);
        return List.of(
            Map.of("role", "system", "content", SYSTEM_PROMPT),
            Map.of("role", "user", "content", userContent)
        );
    }

    private String buildUserContent(ActionSpec spec, String instruction, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：").append(spec.label()).append('\n');
        sb.append("要求：").append(spec.task()).append('\n');
        if (!spec.constraints().isBlank()) {
            sb.append("约束：").append(spec.constraints()).append('\n');
        }
        sb.append("输出长度：").append(spec.lengthHint()).append('\n');
        if (instruction != null && !instruction.isBlank()) {
            sb.append("附加约束：").append(instruction.trim()).append('\n');
        }
        sb.append("\n---\n\n原文：\n").append(text);
        return sb.toString();
    }

    /** 流式事件 — endpoint 映射为 SSE 协议 */
    public sealed interface AssistEvent {
        record Token(String text) implements AssistEvent {}
        record Done() implements AssistEvent {}
        record Error(String message) implements AssistEvent {}

        static AssistEvent token(String t) { return new Token(t); }
        static AssistEvent done() { return new Done(); }
        static AssistEvent error(String m) { return new Error(m); }
    }

    /** Per-action 行为规格 */
    private record ActionSpec(
        String label,
        String task,
        String constraints,
        String lengthHint
    ) {}
}

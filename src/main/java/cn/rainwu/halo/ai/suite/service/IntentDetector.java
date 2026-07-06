package cn.rainwu.halo.ai.suite.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import cn.rainwu.halo.ai.suite.config.AIProperties;
import cn.rainwu.halo.ai.suite.extension.IntentRoute;
import cn.rainwu.halo.ai.suite.llm.LlmClient;
import cn.rainwu.halo.ai.suite.llm.UsageScenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 意图识别器 — 决定用户问题该走「自定义意图 pipeline」还是「普通 RAG」.
 * <p>
 * 策略：正则优先 + LLM 兜底（首版配置项 {@code llmFallback=true} 时才触发 LLM）.
 * <ol>
 *   <li>从 {@link IntentRouteService#listEnabledIntents()} 拿所有启用意图（按 priority 降序）</li>
 *   <li>依次匹配 {@code triggerPatterns}（任一正则命中即返回该意图）</li>
 *   <li>正则全没命中，且存在 {@code llmFallback=true} 的意图时，调一次 LLM 分类</li>
 *   <li>LLM 返回意图 ID 或 "none"（兜底走 RAG）；LLM 失败/超时 → 返回 empty（走 RAG）</li>
 * </ol>
 * <p>
 * 替代原硬编码意图逻辑（热门文章已迁移成内置意图模板）.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentDetector {

    private static final Duration LLM_DETECT_TIMEOUT = Duration.ofSeconds(2);

    private final IntentRouteService intentRouteService;
    private final LlmClient llmClient;
    private final AIProperties aiProperties;

    /**
     * 检测用户问题命中的意图.
     *
     * @return 命中的 IntentRoute（empty 表示未命中，走默认 RAG 流程）
     */
    public Mono<Optional<IntentRoute>> detect(String userQuery, List<Map<String, String>> history) {
        if (userQuery == null || userQuery.isBlank()) {
            return Mono.just(Optional.empty());
        }
        return intentRouteService.listEnabledIntents()
            .flatMap(intents -> {
                if (intents.isEmpty()) {
                    return Mono.just(Optional.<IntentRoute>empty());
                }

                // Step 1：正则匹配（按 priority 顺序）
                for (IntentRoute route : intents) {
                    if (matchesRegex(userQuery, route)) {
                        log.debug("[IntentDetector] 正则命中意图: {} (query='{}')",
                            routeId(route), userQuery);
                        return Mono.just(Optional.of(route));
                    }
                }

                // Step 2：LLM 兜底（仅对声明了 llmFallback=true 的意图）
                List<IntentRoute> fallbackCandidates = intents.stream()
                    .filter(r -> r.getSpec() != null
                        && Boolean.TRUE.equals(r.getSpec().getLlmFallback()))
                    .toList();
                if (fallbackCandidates.isEmpty()) {
                    return Mono.just(Optional.<IntentRoute>empty());
                }
                return detectByLlm(userQuery, fallbackCandidates);
            })
            .onErrorResume(e -> {
                log.warn("[IntentDetector] 意图识别失败，走默认 RAG: {}", e.getMessage());
                return Mono.just(Optional.empty());
            });
    }

    private boolean matchesRegex(String query, IntentRoute route) {
        if (route.getSpec() == null || route.getSpec().getTriggerPatterns() == null) {
            return false;
        }
        for (String pattern : route.getSpec().getTriggerPatterns()) {
            if (pattern == null || pattern.isBlank()) continue;
            try {
                // 关键词正则：不区分大小写，部分匹配（find）
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(query).find()) {
                    return true;
                }
            } catch (PatternSyntaxException e) {
                // 配置错误的正则降级为字面量包含
                if (query.toLowerCase().contains(pattern.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Mono<Optional<IntentRoute>> detectByLlm(String userQuery,
                                                     List<IntentRoute> candidates) {
        return aiProperties.getModelConfig().flatMap(modelConfig -> {
            // 构建 LLM 候选列表 + hint
            StringBuilder candList = new StringBuilder();
            StringBuilder hints = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                IntentRoute r = candidates.get(i);
                candList.append(i + 1).append(". ")
                    .append(routeId(r)).append(" - ")
                    .append(r.getSpec() != null ? r.getSpec().getDisplayName() : "")
                    .append("\n");
                String hint = r.getSpec() != null ? r.getSpec().getLlmFallbackHint() : null;
                if (hint != null && !hint.isBlank()) {
                    hints.append(routeId(r)).append(": ").append(hint).append("\n");
                }
            }
            String systemPrompt = """
                你是意图分类器。根据用户问题判断应该走哪个意图.
                只返回意图 ID（如 builtin-latest-posts），或 none 表示都不匹配.
                不要解释，只输出 ID 或 none.
                可选意图：
                """ + candList + "\n判断提示：\n" + hints;

            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userQuery)
            );

            return llmClient.chat(
                    modelConfig.getEffectiveChatModel(),
                    messages,
                    0.0f,
                    32,
                    null,
                    null,
                    UsageScenario.INTENT_DETECT
                )
                .timeout(LLM_DETECT_TIMEOUT)
                .map(answer -> {
                    String id = answer == null ? "" : answer.trim().toLowerCase();
                    if (id.isEmpty() || "none".equals(id)) {
                        return Optional.<IntentRoute>empty();
                    }
                    // 匹配返回的 ID（或序号）
                    for (IntentRoute r : candidates) {
                        if (routeId(r).equalsIgnoreCase(id)) {
                            log.debug("[IntentDetector] LLM 命中意图: {} (query='{}')",
                                routeId(r), userQuery);
                            return Optional.of(r);
                        }
                    }
                    // 尝试按返回的数字匹配
                    try {
                        int idx = Integer.parseInt(id.replaceAll("[^0-9]", ""));
                        if (idx >= 1 && idx <= candidates.size()) {
                            return Optional.of(candidates.get(idx - 1));
                        }
                    } catch (NumberFormatException ignore) {}
                    return Optional.<IntentRoute>empty();
                })
                .onErrorResume(e -> {
                    log.debug("[IntentDetector] LLM 兜底失败/超时，走默认 RAG: {}",
                        e.getMessage());
                    return Mono.just(Optional.<IntentRoute>empty());
                });
        });
    }

    private static String routeId(IntentRoute route) {
        return route.getMetadata() != null ? route.getMetadata().getName() : "?";
    }
}

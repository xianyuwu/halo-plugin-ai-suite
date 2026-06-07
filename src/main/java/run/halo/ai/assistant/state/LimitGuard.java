package run.halo.ai.assistant.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.assistant.config.AIProperties;
import run.halo.ai.assistant.endpoint.UsageLimit.LimitDecision;
import run.halo.ai.assistant.endpoint.UsageLimit.UsageLimitsConfig;
import run.halo.ai.assistant.endpoint.UsageLimit.VisitorRateLimitConfig;

/**
 * 用量限流守卫 — 每次 LLM 调用前查 limits, 超限抛 {@link run.halo.ai.assistant.endpoint.UsageLimit.LimitExceededException}.
 *
 * <p>检查顺序(任一超限即拒):
 * <ol>
 *   <li>enabled = false → 放行</li>
 *   <li>访客限流 (按 IP) → 超限则拒</li>
 *   <li>对话模型单模型日上限 → 超限则拒</li>
 * </ol>
 *
 * <p>调用方约定: LlmClient 在每个公开方法入口同步 evaluate, 命中拒绝后抛
 * {@code LimitExceededException}, 由上游 service 的 onErrorResume 转成错误响应.
 *
 * <p>clientIp 由 LlmClient 从 reactor context 读, PublicChatEndpoint 在入口
 * .contextWrite 注入. evaluate 同步读, 不需要 ThreadLocal (避免 reactor 跨线程失效).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LimitGuard {

    private final AIProperties aiProperties;
    private final UsageTracker usageTracker;
    private final VisitorRateLimiter visitorRateLimiter;

    /**
     * 同步检查(包装成 Mono 给 LlmClient 链式调用).
     *
     * @param model       模型名 (chat/embed/rerank 都传)
     * @param requestType 请求类型: "chat" / "embed" / "rerank"
     * @param clientIp    访客 IP (从 reactor context 读), 用于访客限流. null/空表示跳过
     */
    public Mono<LimitDecision> check(String model, String requestType, String clientIp) {
        return aiProperties.getUsageLimitsConfig()
            .zipWith(aiProperties.getVisitorRateLimitConfig())
            .map(tuple -> evaluate(tuple.getT1(), tuple.getT2(), model, requestType, clientIp))
            .defaultIfEmpty(LimitDecision.ok())
            .onErrorResume(e -> {
                log.warn("[LimitGuard] 读取 limits 失败, 默认放行: {}", e.getMessage());
                return Mono.just(LimitDecision.ok());
            });
    }

    /**
     * 评估逻辑 — 拆出来便于单测.
     *
     * <p>简化原则: 只对"对话"(chat)类型限流 token 总量. 嵌入/重排序不查 token 限流
     * (成本可忽略, 限了反而干扰索引流程). 访客限流 (按 IP) 任何 chat 都生效.
     */
    LimitDecision evaluate(UsageLimitsConfig cfg, VisitorRateLimitConfig vcfg,
                           String model, String requestType, String clientIp) {
        // 0) 总开关
        if (!cfg.enabled() && (vcfg == null || !vcfg.enabled())) return LimitDecision.ok();

        // 1) 对话 token 限流 — 只查 chat (嵌入/重排序不受 token 限流)
        if (!"chat".equals(requestType)) return LimitDecision.ok();
        if (model == null) return LimitDecision.ok();

        // 2) 访客限流 (按 IP) — 任何 chat 都生效, cfg 由反应式链注入
        if (clientIp != null && !clientIp.isBlank()) {
            LimitDecision vd = visitorRateLimiter.check(clientIp, vcfg);
            if (!vd.allowed()) return vd;
        }

        // 3) 对话模型 token 上限
        if (!cfg.enabled()) return LimitDecision.ok();
        Long limit = cfg.chatModelLimits().get(model);
        if (limit == null || limit <= 0) return LimitDecision.ok();

        long modelTokens = usageTracker.getTodayTokens(model);
        if (modelTokens >= limit) {
            return LimitDecision.reject(
                "对话模型 " + model + " 当日 token 已达上限 " + limit
                    + " (已用 " + modelTokens + ")", model);
        }
        return LimitDecision.ok();
    }
}

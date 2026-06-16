package run.halo.ai.suite.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.endpoint.UsageLimit.LimitDecision;
import run.halo.ai.suite.endpoint.UsageLimit.UsageLimitsConfig;
import run.halo.ai.suite.endpoint.UsageLimit.VisitorRateLimitConfig;

/**
 * 用量限流守卫 — 每次 LLM 调用前查 limits, 超限抛 {@link run.halo.ai.suite.endpoint.UsageLimit.LimitExceededException}.
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
     * 并发预扣保守估值(token). 单次 chat 请求 prompt+completion 通常在此量级,
     * 用作"路上请求"的占位, 防止并发请求同时通过限额检查导致实际超限.
     * 不要求精确: 预占只是占位, 请求返回后由 settle 结算实际用量.
     */
    private static final long PER_REQUEST_RESERVE_TOKENS = 1000;

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
     *
     * <p>开关独立性: token 限流({@code cfg.enabled})与访客限流({@code vcfg.enabled})
     * 是两个独立开关 — 关闭 token 限流不应连带关闭访客限流, 反之亦然.
     *
     * <p>并发预扣: token 限流通过时预占 {@link #PER_REQUEST_RESERVE_TOKENS},
     * 避免"检查即通过"在并发下被绕过(两个请求同时看到未超限快照而全部放行).
     * 预占通过 {@link LimitDecision#reservedTokens} 返回, 调用方须在请求结束后结算.
     */
    LimitDecision evaluate(UsageLimitsConfig cfg, VisitorRateLimitConfig vcfg,
                           String model, String requestType, String clientIp) {
        // 只对 chat 类型限流 (嵌入/重排序不限流)
        if (!"chat".equals(requestType)) return LimitDecision.ok();
        if (model == null) return LimitDecision.ok();

        // 1) 访客限流 (按 IP) — 独立开关, 任何 chat 都生效, 不受 cfg.enabled 影响
        if (vcfg != null && vcfg.enabled() && clientIp != null && !clientIp.isBlank()) {
            LimitDecision vd = visitorRateLimiter.check(clientIp, vcfg);
            if (!vd.allowed()) return vd;
        }

        // 2) 对话模型 token 上限 — 独立开关
        if (cfg == null || !cfg.enabled()) return LimitDecision.ok();
        Long limit = cfg.chatModelLimits() == null ? null : cfg.chatModelLimits().get(model);
        if (limit == null || limit <= 0) return LimitDecision.ok();

        // 用"已用 + 已预占"判断, 防止并发请求同时通过限额检查
        long modelTokens = usageTracker.getTodayTokensWithReservation(model);
        if (modelTokens >= limit) {
            return LimitDecision.reject(
                "对话模型 " + model + " 当日 token 已达上限 " + limit
                    + " (已用+预占 " + modelTokens + ")", model);
        }
        // 通过则预占, 调用方负责结算
        usageTracker.reserve(model, PER_REQUEST_RESERVE_TOKENS);
        return LimitDecision.allowWithReservation(PER_REQUEST_RESERVE_TOKENS);
    }
}

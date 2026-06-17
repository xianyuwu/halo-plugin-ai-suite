package run.halo.ai.suite.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.ai.suite.config.AIProperties;
import run.halo.ai.suite.endpoint.UsageLimit.LimitDecision;
import run.halo.ai.suite.endpoint.UsageLimit.UsageLimitsConfig;
import run.halo.ai.suite.endpoint.UsageLimit.VisitorRateLimitConfig;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * LimitGuard 单测 — 重点覆盖本轮 D 修复:
 * 1) token 限流与访客限流的 enabled 开关解耦(原实现关闭 token 限流会连带关闭访客限流)
 * 2) token 限流改用"已用+预占"判断并在通过时 reserve 预占
 */
class LimitGuardTest {

    private final UsageTracker usageTracker = new UsageTracker(mock(ReactiveExtensionClient.class));
    private final VisitorRateLimiter visitorRateLimiter = new VisitorRateLimiter();
    private final LimitGuard guard = new LimitGuard(mock(AIProperties.class), usageTracker, visitorRateLimiter);

    /** token 限流启用, limit=5000 */
    private static UsageLimitsConfig tokenCfg() {
        return new UsageLimitsConfig(true, Map.of("gpt-4", 5000L));
    }

    /** 访客限流启用, daily=5 hourly=2 */
    private static VisitorRateLimitConfig visitorCfg() {
        return new VisitorRateLimitConfig(true, 5, 2, List.of());
    }

    // ===== 开关解耦测试 (D 修复核心) =====

    /**
     * D 修复核心: token 限流关闭(cfg.enabled=false)时, 访客限流仍应独立生效.
     * 原实现: !cfg.enabled 直接 return ok(), 连带关闭了访客限流 → 可被刷量.
     */
    @Test
    void visitorLimitStillActiveWhenTokenLimitDisabled() {
        UsageLimitsConfig tokenDisabled = new UsageLimitsConfig(false, Map.of());
        // 用光访客 hourly 限额(2 次)
        assertThat(guard.evaluate(tokenDisabled, visitorCfg(), "gpt-4", "chat", "1.1.1.1").allowed()).isTrue();
        assertThat(guard.evaluate(tokenDisabled, visitorCfg(), "gpt-4", "chat", "1.1.1.1").allowed()).isTrue();
        // 第 3 次应被访客 hourly 拦截, 即使 token 限流已关闭
        LimitDecision d = guard.evaluate(tokenDisabled, visitorCfg(), "gpt-4", "chat", "1.1.1.1");
        assertThat(d.allowed()).as("token 限流关闭, 但访客限流应仍生效拦截").isFalse();
        assertThat(d.reason()).contains("频繁");
    }

    /**
     * 反向解耦: 访客限流关闭(vcfg.enabled=false)时, token 限流仍应独立生效.
     */
    @Test
    void tokenLimitStillActiveWhenVisitorLimitDisabled() {
        VisitorRateLimitConfig visitorDisabled = new VisitorRateLimitConfig(false, 0, 0, List.of());
        // 预占 + 记录用量, 让 token 用量逼近限额
        for (int i = 0; i < 5; i++) {
            usageTracker.reserve("gpt-4", 1000);
            usageTracker.recordUsage("gpt-4", "chat", "test", 1000, 0, false, 0);
            usageTracker.settle("gpt-4", 1000);
        }
        // 已用 5000 = 限额, 应被 token 限流拦截, 即使访客限流已关闭
        LimitDecision d = guard.evaluate(tokenCfg(), visitorDisabled, "gpt-4", "chat", "1.1.1.1");
        assertThat(d.allowed()).as("访客限流关闭, 但 token 限流应仍生效拦截").isFalse();
        assertThat(d.reason()).contains("已达上限");
    }

    // ===== 预占判断测试 (D 修复另一面) =====

    /**
     * D 修复: token 限流通过时应 reserve 预占, 后续请求的判断要算上预占.
     * 场景: limit=5000, 第一个请求预占 1000(内部固定值), 第二个请求应看到 1000+预占.
     */
    @Test
    void tokenLimitReservesOnPass() {
        // 第一次通过, 内部会 reserve PER_REQUEST_RESERVE_TOKENS(1000)
        LimitDecision d1 = guard.evaluate(tokenCfg(), visitorCfg(), "gpt-4", "chat", "9.9.9.9");
        assertThat(d1.allowed()).isTrue();
        // 验证预占已发生: getTodayTokensWithReservation 应 > 0
        assertThat(usageTracker.getTodayTokensWithReservation("gpt-4"))
            .as("通过后应已 reserve 预占").isGreaterThan(0);
    }

    /**
     * D 修复核心: 并发预扣防超限. 用大量并发请求把预占撑到限额, 验证后续被拦截.
     * (这里模拟串行快速调用, 真实并发由 reserve 的 AtomicLong 保证原子性)
     */
    @Test
    void concurrentReservesPreventExceedingLimit() {
        // limit=5000, PER_REQUEST_RESERVE=1000, 理论上 5 次通过后第 6 次应拦截
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            // 每次用不同 IP 避免访客限流干扰
            LimitDecision d = guard.evaluate(tokenCfg(), visitorCfg(), "gpt-4", "chat", "10.0.0." + i);
            if (d.allowed()) allowed++;
        }
        // 最多 5 次通过(5000/1000), 之后因预占累计超限被拦截
        assertThat(allowed).as("预占应防止超过 token 限额").isLessThanOrEqualTo(5);
    }

    // ===== 基础放行/拒绝测试 =====

    @Test
    void allowsChatUnderBothLimits() {
        LimitDecision d = guard.evaluate(tokenCfg(), visitorCfg(), "gpt-4", "chat", "1.2.3.4");
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void nonChatRequestAlwaysAllowed() {
        // embed/rerank 不受 token 限流, 也不受访客限流
        LimitDecision d = guard.evaluate(tokenCfg(), visitorCfg(), "gpt-4", "embed", "1.2.3.4");
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void nullModelAlwaysAllowed() {
        LimitDecision d = guard.evaluate(tokenCfg(), visitorCfg(), null, "chat", "1.2.3.4");
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void bothDisabledAllowsAll() {
        UsageLimitsConfig noToken = new UsageLimitsConfig(false, Map.of());
        VisitorRateLimitConfig noVisitor = new VisitorRateLimitConfig(false, 0, 0, List.of());
        for (int i = 0; i < 10; i++) {
            assertThat(guard.evaluate(noToken, noVisitor, "gpt-4", "chat", "1.2.3.4").allowed()).isTrue();
        }
    }

    @Test
    void rejectsWhenTokenLimitReachedWithoutVisitor() {
        // 不传访客配置, 只测 token
        for (int i = 0; i < 5; i++) {
            usageTracker.reserve("gpt-4", 1000);
            usageTracker.recordUsage("gpt-4", "chat", "test", 1000, 0, false, 0);
            usageTracker.settle("gpt-4", 1000);
        }
        LimitDecision d = guard.evaluate(tokenCfg(), null, "gpt-4", "chat", null);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("gpt-4");
    }
}

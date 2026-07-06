package cn.rainwu.halo.ai.suite.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * UsageTracker 单测 — 重点覆盖本轮 A 修复:
 * reserve/settle 预扣对账机制, 防止并发请求"检查即通过"绕过 token 限额.
 *
 * <p>说明: reserve/settle/getTodayTokensWithReservation/recordUsage 都是纯内存操作,
 * 不触碰 ReactiveExtensionClient(它只在持久化时用), 所以用 mock 传入即可.
 */
class UsageTrackerTest {

    private final UsageTracker tracker = new UsageTracker(mock(ReactiveExtensionClient.class));

    @Test
    void reserveAddsToReservation() {
        // 初始: 无用量
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isZero();
        // 预占 1000
        tracker.reserve("gpt-4", 1000);
        // 预占后: 已用+预占 = 1000 (实际用量仍 0, 但预占算进去)
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isEqualTo(1000);
    }

    @Test
    void settleReleasesReservation() {
        tracker.reserve("gpt-4", 1000);
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isEqualTo(1000);
        // 结算预占
        tracker.settle("gpt-4", 1000);
        // 预占应被清零
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isZero();
    }

    /**
     * 核心场景: recordUsage 后, 实际用量应计入 getTodayTokens,
     * 且预占应被 settle 清掉(对账成功).
     */
    @Test
    void recordUsageSettlesReservationAndCountsActual() {
        tracker.reserve("gpt-4", 1000);
        // 记录实际用量: prompt=800, completion=200, 总计 1000
        tracker.recordUsage("gpt-4", "chat", "test", 800, 200, false, 0);
        // getTodayTokens 应反映实际用量 1000
        assertThat(tracker.getTodayTokens("gpt-4")).isEqualTo(1000);
        // getTodayTokensWithReservation = 实际 1000 + 预占(已 settle 清) = 1000
        // 注意: recordUsage 本身不调 settle(对账由 LlmClient 在 recordUsage 后显式调 settle).
        // 这里单独验证 settle 后预占清零.
        tracker.settle("gpt-4", 1000);
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isEqualTo(1000);
    }

    /**
     * 并发预扣防超限的核心: 两个请求各预占 1000, 在限额 1500 下,
     * getTodayTokensWithReservation 应反映两个预占总和 2000, 从而让第二个请求的检查命中超限.
     */
    @Test
    void multipleReservesAccumulate() {
        tracker.reserve("gpt-4", 1000);
        tracker.reserve("gpt-4", 1000);
        // 两个预占累计 2000, 超过 1500 限额 → 第二个请求的限额检查应拦截
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isEqualTo(2000);
    }

    @Test
    void settleClampsToZeroNeverNegative() {
        tracker.reserve("gpt-4", 500);
        // settle 超过预占量(模拟 recordUsage 失败路径重复 settle)
        tracker.settle("gpt-4", 800);
        tracker.settle("gpt-4", 800);
        // 预占不应为负, 应 clamp 到 0
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isZero();
    }

    @Test
    void settleWithZeroOrNegativeIsNoop() {
        tracker.reserve("gpt-4", 1000);
        tracker.settle("gpt-4", 0);
        tracker.settle("gpt-4", -5);
        // 0 或负的 settle 不应影响预占
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isEqualTo(1000);
    }

    @Test
    void differentModelsTrackedSeparately() {
        tracker.reserve("gpt-4", 1000);
        tracker.reserve("claude", 500);
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isEqualTo(1000);
        assertThat(tracker.getTodayTokensWithReservation("claude")).isEqualTo(500);
    }

    @Test
    void reserveZeroOrNegativeIsNoop() {
        tracker.reserve("gpt-4", 0);
        tracker.reserve("gpt-4", -10);
        assertThat(tracker.getTodayTokensWithReservation("gpt-4")).isZero();
    }
}

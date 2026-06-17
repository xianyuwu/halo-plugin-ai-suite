package run.halo.ai.suite.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.ai.suite.endpoint.UsageLimit.LimitDecision;
import run.halo.ai.suite.endpoint.UsageLimit.VisitorRateLimitConfig;

/**
 * VisitorRateLimiter 单测 — 重点覆盖本轮修复:
 * hourly 超限时回退 daily 计数, 避免正常用户反复触达 hourly 上限被 daily 持续累加导致误封.
 */
class VisitorRateLimiterTest {

    private final VisitorRateLimiter limiter = new VisitorRateLimiter();

    /** 配置: daily=5, hourly=2 */
    private static VisitorRateLimitConfig cfg() {
        return new VisitorRateLimitConfig(true, 5, 2, List.of());
    }

    @Test
    void allowsWhenBothLimitsNotReached() {
        LimitDecision d1 = limiter.check("1.1.1.1", cfg());
        LimitDecision d2 = limiter.check("1.1.1.1", cfg());
        assertThat(d1.allowed()).isTrue();
        assertThat(d2.allowed()).isTrue();
    }

    @Test
    void rejectsWhenHourlyLimitReached() {
        // hourly=2, 前两次通过, 第三次应被 hourly 拦截
        limiter.check("2.2.2.2", cfg());
        limiter.check("2.2.2.2", cfg());
        LimitDecision d = limiter.check("2.2.2.2", cfg());
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("频繁");
    }

    /**
     * 本轮 C 修复的核心测试: hourly 超限时必须回退 daily 计数.
     * 场景: hourly=2, daily=5. 反复触达 hourly 上限不应让 daily 计数持续增长.
     * 若不回退, 跑 5 次 hourly 超限就会把 daily 从 0 撑到 5, 导致正常用户被当日误封.
     */
    @Test
    void rollsBackDailyCountWhenHourlyRejects() {
        String ip = "3.3.3.3";
        // 前 2 次通过(daily 计数 +2)
        assertThat(limiter.check(ip, cfg()).allowed()).isTrue();
        assertThat(limiter.check(ip, cfg()).allowed()).isTrue();
        // 第 3~10 次: 全部因 hourly 超限被拒. 每次 hourly 拒绝应回退 daily +1.
        for (int i = 0; i < 8; i++) {
            LimitDecision d = limiter.check(ip, cfg());
            assertThat(d.allowed()).as("第 %d 次应被 hourly 拒绝", i + 3).isFalse();
        }
        // 关键断言: 虽然 hourly 反复触发, 但 daily 计数应保持在 2(被持续回退),
        // 而不是被撑到 5 导致 daily 误封. 此时 daily 未超限.
        // 验证方式: 等待 hourly 窗口过期不现实(1小时), 改用换一个全新 IP 验证 daily 逻辑没被污染.
        // 这里通过 "同一 IP 反复 hourly 拒绝后, daily 没有超限" 间接验证:
        // 如果回退失效, daily 会被撑到 5, 第 6 次 hourly 拒绝时 reason 会变成 daily 超限而非 hourly.
        LimitDecision d = limiter.check(ip, cfg());
        // 拒绝原因应是 hourly("频繁") 而非 daily("已达上限")
        assertThat(d.reason()).as("应是 hourly 拒绝, 说明 daily 没被撑爆").contains("频繁");
        assertThat(d.reason()).as("不应是 daily 拒绝").doesNotContain("已达上限");
    }

    @Test
    void rejectsWhenDailyLimitReached() {
        String ip = "4.4.4.4";
        // daily=5, 但 hourly=2 会先拦. 用 dailyOnly 配置测 daily.
        VisitorRateLimitConfig dailyOnly = new VisitorRateLimitConfig(true, 3, 0, List.of());
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check(ip, dailyOnly).allowed()).as("第 %d 次应通过", i + 1).isTrue();
        }
        LimitDecision d = limiter.check(ip, dailyOnly);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("已达上限");
    }

    @Test
    void whitelistedIpAlwaysAllowed() {
        VisitorRateLimitConfig withWhitelist = new VisitorRateLimitConfig(true, 1, 1, List.of("5.5.5.5"));
        // 即使 daily=1 hourly=1, 白名单 IP 不受限
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.check("5.5.5.5", withWhitelist).allowed()).isTrue();
        }
    }

    @Test
    void disabledConfigAlwaysAllows() {
        VisitorRateLimitConfig disabled = new VisitorRateLimitConfig(false, 0, 0, List.of());
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.check("6.6.6.6", disabled).allowed()).isTrue();
        }
    }

    @Test
    void differentIpsTrackedSeparately() {
        // IP A 用掉 hourly=2, 不应影响 IP B
        limiter.check("7.7.7.7", cfg());
        limiter.check("7.7.7.7", cfg());
        assertThat(limiter.check("7.7.7.7", cfg()).allowed()).isFalse();
        // IP B 仍可用
        assertThat(limiter.check("8.8.8.8", cfg()).allowed()).isTrue();
    }
}

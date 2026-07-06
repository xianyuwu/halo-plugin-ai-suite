package cn.rainwu.halo.ai.suite.endpoint;

import java.util.List;
import java.util.Map;

/**
 * 用量统计与限流相关数据类(放在 endpoint 包便于 ConsoleUsageEndpoint 引用).
 *
 * <p>全部用 record 形式表达不可变值,保持轻量.
 */
public final class UsageLimit {

    private UsageLimit() {}

    /**
     * 单模型每日 token 上限配置.
     */
    public record UsageLimitEntry(String model, long dailyTokenLimit) {}

    /**
     * 用量限制总配置(从 ConfigMap 读).
     *
     * <p>设计原则: 简单 — 只针对"对话模型"限流, 因为对话 token 最贵.
     * 嵌入/重排序不走限流 (成本可忽略, 限了反而干扰索引流程).
     *
     * @param enabled         是否启用限流
     * @param chatModelLimits 每个对话模型各自的每日 token 上限; 0 / 缺失 = 不限
     */
    public record UsageLimitsConfig(
        boolean enabled,
        Map<String, Long> chatModelLimits
    ) {
        public static UsageLimitsConfig empty() {
            return new UsageLimitsConfig(false, Map.of());
        }
    }

    /**
     * 访客限流配置 — 按客户端 IP 维度限制对话次数, 防止单用户刷量.
     *
     * @param enabled     是否启用
     * @param dailyLimit  每 IP 每日最多对话次数; 0 = 不限
     * @param hourlyLimit 每 IP 滑动 1 小时窗口内最多对话次数; 0 = 不限
     * @param whitelist   IP 白名单 (精确字符串匹配; "*" 匹配所有). 白名单内 IP 不受限
     */
    public record VisitorRateLimitConfig(
        boolean enabled,
        int dailyLimit,
        int hourlyLimit,
        List<String> whitelist
    ) {
        public static VisitorRateLimitConfig empty() {
            return new VisitorRateLimitConfig(false, 0, 0, List.of());
        }
    }

    /**
     * 单模型用量快照.
     */
    public record ModelUsageSnapshot(
        long promptTokens,
        long completionTokens,
        long calls,
        long failures,
        long embeddingTokens
    ) {
        public long totalTokens() {
            return promptTokens + completionTokens + embeddingTokens;
        }
    }

    /**
     * 模型+日期维度的用量记录, 用于 /usage/today 实时读内存.
     */
    public record ModelUsageRecord(
        String date,
        String model,
        ModelUsageSnapshot usage
    ) {}

    /**
     * 限流决策.
     *
     * @param allowed        是否放行
     * @param reason         拒绝原因(放行时为 null)
     * @param modelName      命中限制的模型名(放行时为 null)
     * @param reservedTokens 本次决策预占的 token 数(用于并发限流预扣对账).
     *                       放行且走了 token 限流路径时 >0, 调用方须在请求结束
     *                       (成功/失败)后调 {@code UsageTracker.settle} 结算;
     *                       拒绝路径或不涉及 token 限流时为 0.
     */
    public record LimitDecision(boolean allowed, String reason, String modelName, long reservedTokens) {
        public static LimitDecision ok() {
            return new LimitDecision(true, null, null, 0);
        }
        public static LimitDecision reject(String reason, String modelName) {
            return new LimitDecision(false, reason, modelName, 0);
        }
        /** 放行并预占 token — 调用方需在请求结束后结算预占 */
        public static LimitDecision allowWithReservation(long reservedTokens) {
            return new LimitDecision(true, null, null, reservedTokens);
        }
    }

    /**
     * 限流命中异常 — 抛出后由 LlmClient 上游 service 的 onErrorResume 转成错误响应.
     */
    public static class LimitExceededException extends RuntimeException {
        private final String modelName;

        public LimitExceededException(String modelName, String message) {
            super(message);
            this.modelName = modelName;
        }

        public String getModelName() {
            return modelName;
        }
    }
}

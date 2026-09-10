package com.unisence.iot.rule.sdk;

/**
 * 窗口容量边界（{@code kafka-streams-window-design.md} §十一，定值见 capacity-benchmark.md §3.1）。
 *
 * <p>与 {@link RuleScriptLimits} 同为纯 Java 值对象，配置键 {@code app.rule.window.*}，
 * admin（保存校验）与 engine（快照加载）两侧必须逐项一致，不一致时以 engine 为准。
 *
 * <p><b>为什么这些必须在保存时判定，而不是运行时兜底</b>：窗口参数直接相乘决定 worker 状态内存
 * 与 state Topic 写入的规模（§七的成本模型）。「1 小时窗口 / 1 秒步长」在配置上只是两个数字，
 * 落到运行时却是 3600 倍状态放大 —— 等到状态内存撑爆再报错，已经影响到同实例其它规则了。
 *
 * @param maxWindowMillis  最大窗口长度。超过一天的累计属于报表，应走时序数据库查询而非常驻窗口状态
 * @param minAdvanceMillis 最小滑动步长，与 {@code maxAmplification} 共同封顶重叠窗口数
 * @param maxAmplification 最大 {@code sizeMillis / advanceMillis}。一条消息同时落入的窗口数上界，
 *                         直接等比放大状态与 state Topic 写入
 * @param maxGraceMillis   最大迟到宽限期。迟到超过此值的数据按补录处理，不进实时窗口
 */
public record RuleWindowLimits(
    long maxWindowMillis,
    long minAdvanceMillis,
    int maxAmplification,
    long maxGraceMillis) {

    /**
     * 定值（2026-07-29，capacity-benchmark.md §3.1）。
     */
    public static final RuleWindowLimits DEFAULT =
        new RuleWindowLimits(24 * 60 * 60_000L, 10_000L, 12, 10 * 60_000L);

    public RuleWindowLimits {
        requirePositive(maxWindowMillis, "maxWindowMillis");
        requirePositive(minAdvanceMillis, "minAdvanceMillis");
        requirePositive(maxAmplification, "maxAmplification");
        requirePositive(maxGraceMillis, "maxGraceMillis");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("app.rule.window." + name + " 必须为正数, 实际=" + value);
        }
    }
}

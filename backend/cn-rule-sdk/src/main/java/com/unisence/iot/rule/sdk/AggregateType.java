package com.unisence.iot.rule.sdk;

/**
 * 聚合类型：决定「如何计算窗口内的数据」（{@code aggregate_config.type}）。
 *
 * <p>count/sum/min/max/avg 必须使用增量 accumulator，禁止保存窗口完整消息。
 *
 * <p>{@code NONE} 已删除（2026-08-03）：它原本表示「无窗口因此无聚合」，
 * 而「无窗口」现在由即时规则表表达，窗口规则必须有聚合 —— 攒了数据无人计算的窗口
 * 只是在白占窗口状态内存。即时规则的聚合结果由 {@link AggregateResult#none()} 给出，
 * 其 {@code type} 为 null，含义是「本次输出不来自聚合」。
 */
public enum AggregateType {

    COUNT,
    SUM,
    MIN,
    MAX,
    AVG,
    FIRST,
    LAST,
    CHANGE_RATE;

    /**
     * 是否需要从 payload 中取一个数值（即需要配置 valueIdentifier）。
     */
    public boolean requiresValue() {
        return this != COUNT;
    }

    /**
     * 是否产出 {@link AggregateResult#sum()}。
     */
    public boolean producesSum() {
        return this == SUM || this == AVG;
    }
}

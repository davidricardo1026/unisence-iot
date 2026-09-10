package com.unisence.iot.rule.config;

/**
 * 档位条件的表达方式（{@code us_iot_rule_level.condition_kind}）。
 */
public enum ConditionKind {

    /**
     * 数值阈值比较：{@code operator} + {@code threshold}。
     *
     * <p>比较对象由规则类别决定 —— 即时规则比较 {@code value_config} 指向的消息字段，
     * 窗口规则比较聚合结果（{@code AggregateResult.numericResult()}）。
     */
    THRESHOLD,
    /**
     * Groovy 脚本判定，返回 Boolean。<b>仅即时规则可用</b>。
     *
     * <p>用途是非数值条件（状态字段等于某个枚举值、多字段组合判断），
     * 这些无法用 operator + threshold 表达。
     *
     * <p>窗口规则不开放：窗口档位的比较对象是聚合结果，而聚合结果已经是一个数值，
     * 用脚本重新算一遍等于把聚合语义搬进脚本 —— 那正是窗口规则要避免的
     * （脚本里累计 count/sum 是 {@code RuleFilter} 明令禁止的行为）。
     */
    SCRIPT
}

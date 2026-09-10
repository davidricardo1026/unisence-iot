package com.unisence.iot.rule.config;

/**
 * 即时规则被监控信号的取值配置（{@code us_iot_rule_instant.value_config}）。
 *
 * <p>与窗口规则的 {@link AggregateConfig} 是同一件事的两种形态：都回答「拿哪个字段的数」。
 * 区别只在窗口规则还要回答「怎么聚合」，因此那边多一个 {@code type}。
 *
 * <p>刻意<b>不</b>复用 {@code AggregateConfig} 并把 type 置空：那会让「即时规则的聚合类型」
 * 成为一个必须永远为空、却又语法上可填的字段 —— 正是本次重设计要消除的形态。
 *
 * @param valueIdentifier 属性 identifier 或事件参数名
 * @param valueSource     取值来源；有 valueIdentifier 时必填
 */
public record ValueConfig(String valueIdentifier, ValueSource valueSource) {

    public boolean hasValue() {
        return valueIdentifier != null && !valueIdentifier.isBlank();
    }
}

package com.unisence.iot.rule.config;

import com.unisence.iot.rule.sdk.AggregateType;

/**
 * {@code us_iot_rule_window.aggregate_config} 的字段级模型。
 *
 * <p>字段名从契约初稿的 {@code valuePath} 改为 {@code valueIdentifier}：
 * 上下文取值一律按 identifier 索引（{@code ctx.numberValue(id)}），不存在嵌套路径语义，
 * 叫「path」会诱导实现方去做 JSONPath 解析。
 *
 * <p>{@code type} 从原来的独立列并入本 record：它和取值配置是同一件事的两半
 * （「怎么算」与「算什么」），分开存会让「AVG 却没配 valueIdentifier」这类组合
 * 需要跨列校验，而合在一起时它就是一个 record 内的必填关系。
 *
 * @param type            聚合类型，必填。窗口不配聚合等于攒了数据无人计算，纯占窗口状态内存
 * @param valueIdentifier 参与聚合的属性 identifier 或事件参数名；{@code COUNT} 不需要
 * @param valueSource     取值来源；有 valueIdentifier 时必填
 */
public record AggregateConfig(AggregateType type, String valueIdentifier, ValueSource valueSource) {

    /**
     * {@code COUNT} 只数条数，不取值。
     */
    public static AggregateConfig ofCount() {
        return new AggregateConfig(AggregateType.COUNT, null, null);
    }

    public boolean hasValue() {
        return valueIdentifier != null && !valueIdentifier.isBlank();
    }
}

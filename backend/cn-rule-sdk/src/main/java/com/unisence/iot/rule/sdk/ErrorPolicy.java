package com.unisence.iot.rule.sdk;

/**
 * 规则执行失败时的处理策略（{@code us_iot_rule.error_policy}）。
 */
public enum ErrorPolicy {

    /**
     * 默认/关键规则：整条消息进入 DLQ，不提交动作。
     */
    DLQ_MESSAGE,
    /**
     * 非关键旁路：记录失败并继续下一条规则。
     */
    SKIP_RULE,
    /**
     * 明确的数据过滤：记录审计指标后停止，不进 DLQ。
     */
    DROP_MESSAGE
}

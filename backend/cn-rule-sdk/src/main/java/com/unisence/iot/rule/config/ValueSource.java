package com.unisence.iot.rule.config;

/**
 * 聚合取值来源（{@code aggregate_config.valueSource}）。
 */
public enum ValueSource {

    /**
     * 取 payload.values[identifier]，对应 messageType=property。
     */
    PROPERTY,
    /**
     * 取 payload.params[identifier]，对应 messageType=event。
     */
    EVENT_PARAM
}

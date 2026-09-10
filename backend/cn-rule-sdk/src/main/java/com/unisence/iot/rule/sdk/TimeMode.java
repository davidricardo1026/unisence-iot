package com.unisence.iot.rule.sdk;

/**
 * 窗口时间语义（{@code window_config.timeMode}）。
 */
public enum TimeMode {

    /**
     * 以 engine 接收时刻推进窗口。
     */
    PROCESSING_TIME,
    /**
     * 以消息信封 occurredAt 推进窗口，必须同时定义 grace 与迟到数据策略。
     */
    EVENT_TIME;

    public boolean requiresGrace() {
        return this == EVENT_TIME;
    }
}

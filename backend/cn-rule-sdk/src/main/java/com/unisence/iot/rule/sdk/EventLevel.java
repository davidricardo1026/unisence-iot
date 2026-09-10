package com.unisence.iot.rule.sdk;

/**
 * 物模型事件等级（{@code us_iot_tm_event.event_type} 取值域的全仓唯一定义）。
 *
 * <p>DDL 是 {@code tinyint}，取值 1-info / 2-warning / 3-error，与时序表
 * {@code evt_*} 表 {@code event_type} FIELD 同源。
 *
 * <p><b>等级只从物模型取，不信报文</b>（thing-model-design.md §十一）：等级决定告警与通知行为，
 * 若允许设备自报，一台固件有问题的设备就能把所有事件标成 error 淹没告警系统。
 */
public enum EventLevel {

    INFO(1),
    WARNING(2),
    ERROR(3);

    private final int code;

    EventLevel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static EventLevel fromCode(int code) {
        for (EventLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知的事件等级: " + code);
    }
}

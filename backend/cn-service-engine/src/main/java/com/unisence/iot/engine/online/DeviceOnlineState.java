package com.unisence.iot.engine.online;

/**
 * {@code us_iot_device.status} 取值域的唯一 Java 定义
 * （device-module/device-online-state-design.md §二）。
 *
 * <p>{@link #UNKNOWN} 表达的是「平台失去了观测能力」，不是设备故障：驱动失联时其名下设备
 * 全部转入该态，**不写 {@code device_online_log}、不告警**。把观测能力缺失记成设备离线，
 * 会让故障排查时看到一堆与事实不符的历史。
 */
public enum DeviceOnlineState {

    INACTIVE(0),
    ONLINE(1),
    OFFLINE(2),
    UNKNOWN(3);

    private final int code;

    DeviceOnlineState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * Redis state HASH 中存的就是 code 的字符串形式。
     */
    public String codeText() {
        return Integer.toString(code);
    }

    public static DeviceOnlineState fromCode(int code) {
        for (DeviceOnlineState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知的设备状态码: " + code);
    }
}

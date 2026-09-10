package com.unisence.iot.message.type;

/**
 * 设备心跳自报健康度。
 *
 * <p><b>⚠ 取值域待 device-module 确认</b>（device-message-contract.md §十一）：
 * 当前三档是按语义补齐的，须与设备在线状态模型对齐后固化。
 *
 * <p>注意这是**设备自报**的健康度，不是平台判定的在线/离线状态 ——
 * 后者由心跳租约超时推导，不能由设备上报值决定。
 */
public enum HeartbeatStatus {

    /**
     * 正常。心跳未携带 status 时的默认值。
     */
    ONLINE("online"),
    /**
     * 降级运行：设备自检发现异常但仍可工作。
     */
    DEGRADED("degraded"),
    /**
     * 故障：设备自检失败。
     */
    FAULT("fault");

    private final String code;

    HeartbeatStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static HeartbeatStatus fromCode(String code) {
        for (HeartbeatStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的心跳状态编码: " + code);
    }
}

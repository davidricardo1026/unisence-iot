package com.unisence.iot.message;

/**
 * 会进入规则窗口的设备消息。
 *
 * <p>{@code deliveryEpoch + deliverySequence} 是发送端交付序号，不替代 {@code msgId}：
 * 前者用于窗口入口精确去重，后者继续承担审计与规则输出幂等身份。
 */
public sealed interface SequencedDeviceMessage extends DeviceMessage
    permits DevicePropertyMessage, DeviceEventMessage {

    /**
     * 同一设备消息流的发送纪元；发送端重建序号空间前必须先增加纪元并 fencing 旧发送者。
     */
    long deliveryEpoch();

    /**
     * 纪元内单调递增的交付序号；首次发送分配，所有重试必须复用。
     */
    long deliverySequence();
}

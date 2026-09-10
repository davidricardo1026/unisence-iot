package com.unisence.iot.rule.sdk;

import com.unisence.iot.message.type.MessageType;

/**
 * 规则脚本可见的信封<b>只读投影</b>（device-message-contract.md §八）。
 *
 * <p>不是线上模型 —— 那是 {@code com.unisence.iot.message.IotMessage}，由 engine 在 {@code RuleProcessor}
 * 边界映射成本记录。刻意保留这层投影而不让脚本直接持有 {@code IotMessage}，是访问控制而非重复：
 * 前者会让脚本 {@code switch} 出 {@code DeviceCreateMessage.formData()}，
 * 而那里含 {@code enc:v*:} 加密信封，{@code DeviceSnapshot.formData} 剔掉的 sensitive 字段会从另一条路漏回去。
 * 本记录只有标量字段、不含任何 payload map；读 payload 必须走 {@link MessageContext} 的类型化访问器。
 *
 * @param schemaVersion 线上键 {@code v}；未知主版本拒绝
 * @param msgId         线上键 {@code id}；全局唯一 UUID/ULID，最长 36 字符，重试必须复用
 * @param productKey    线上键 {@code pk}
 * @param deviceCode    线上键 {@code dc}；服务心跳为 null
 * @param occurredAt    线上键 {@code ts}；业务发生时间 epoch millis
 * @param source        线上键 {@code src}；驱动/网关/服务实例标识
 * @param identifier    事件 identifier；属性、创建与心跳为 null
 */
public record MessageEnvelope(
    int schemaVersion,
    String msgId,
    String productKey,
    String deviceCode,
    long occurredAt,
    String source,
    MessageType messageType,
    String identifier) {

    public MessageEnvelope {
        if (messageType == null) {
            throw new IllegalArgumentException("messageType 不可为空");
        }
        // 只有服务心跳没有设备维度。MessageType 不再提供 requiresDeviceCode() —— 线上模型已按
        // DeviceMessage / ServiceHeartbeatMessage 分层，该判断在那边是编译期的；
        // 本投影是拍平的单一记录，只能在此显式判一次。
        if (messageType != MessageType.SERVICE_HEARTBEAT && (deviceCode == null || deviceCode.isBlank())) {
            throw new IllegalArgumentException("消息类型 " + messageType.code() + " 必须携带 deviceCode");
        }
    }

    /**
     * 设备消息的 Kafka key：productKey.deviceCode。
     */
    public String deviceKey() {
        return productKey + "." + deviceCode;
    }
}

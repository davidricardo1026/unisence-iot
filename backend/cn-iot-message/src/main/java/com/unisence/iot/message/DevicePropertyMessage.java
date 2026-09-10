package com.unisence.iot.message;

import com.unisence.iot.message.type.MessageType;

import java.util.Map;

/**
 * 属性上报（线上 {@code mt=property}）。
 *
 * <p>空 map 直接拒绝：一条不携带任何属性值的属性消息没有业务含义，
 * 放行只会让下游在窗口聚合里得到一个既非命中也非未命中的空洞。
 *
 * <p>「未知属性 / 类型不符」<b>不在此判定</b> —— 那要读物模型，会迫使本模块依赖数据源，
 * 破坏契约 §一 的依赖 DAG。它属于第 3 层校验，失败时整条进 DLQ，禁止半条落盘（契约 §六）。
 *
 * @param deliveryEpoch 发送纪元，必须为正数；重试必须复用
 * @param deliverySequence 纪元内序号，必须非负；重试必须复用
 * @param values key 为属性 identifier，value 为 MessagePack 原生类型；不可为空 map
 */
public record DevicePropertyMessage(
    int schemaVersion,
    String msgId,
    long occurredAt,
    String source,
    long deliveryEpoch,
    long deliverySequence,
    String productKey,
    String deviceCode,
    Map<String, Object> values) implements SequencedDeviceMessage {

    public DevicePropertyMessage {
        MessageFields.requireEnvelope(schemaVersion, msgId, occurredAt, source);
        MessageFields.requireDelivery(deliveryEpoch, deliverySequence);
        MessageFields.requireDevice(productKey, deviceCode);
        if (values == null || values.isEmpty()) {
            throw new MessageStructureException(MessageErrorCode.PROPERTY_VALUES_EMPTY,
                                                "values", "属性消息必须至少携带一个属性值");
        }
        values = Map.copyOf(values);
    }

    @Override
    public MessageType messageType() {
        return MessageType.PROPERTY;
    }
}

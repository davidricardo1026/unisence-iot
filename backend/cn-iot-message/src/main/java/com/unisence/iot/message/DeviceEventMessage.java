package com.unisence.iot.message;

import com.unisence.iot.message.type.MessageType;

import java.util.Map;

/**
 * 事件上报（线上 {@code mt=event}）。
 *
 * <p><b>刻意不含 level 字段</b>：事件等级从物模型取，不信任上行值。
 * 建模成字段就等于给设备一条把普通事件自报为紧急告警的通路。
 *
 * @param deliveryEpoch 发送纪元，必须为正数；重试必须复用
 * @param deliverySequence 纪元内序号，必须非负；重试必须复用
 * @param identifier 必填；事件 identifier，最长 50 字符
 * @param params     可空；构造后为不可变空 map
 */
public record DeviceEventMessage(
    int schemaVersion,
    String msgId,
    long occurredAt,
    String source,
    long deliveryEpoch,
    long deliverySequence,
    String productKey,
    String deviceCode,
    String identifier,
    Map<String, Object> params) implements SequencedDeviceMessage {

    public static final int MAX_IDENTIFIER_CHARS = 50;

    public DeviceEventMessage {
        MessageFields.requireEnvelope(schemaVersion, msgId, occurredAt, source);
        MessageFields.requireDelivery(deliveryEpoch, deliverySequence);
        MessageFields.requireDevice(productKey, deviceCode);
        if (identifier == null || identifier.isBlank()) {
            throw new MessageStructureException(MessageErrorCode.EVENT_IDENTIFIER_MISSING,
                                                "identifier", "不可为空");
        }
        if (identifier.length() > MAX_IDENTIFIER_CHARS) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_TOO_LONG,
                                                "identifier", identifier.length() + " > " + MAX_IDENTIFIER_CHARS);
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    @Override
    public MessageType messageType() {
        return MessageType.EVENT;
    }
}

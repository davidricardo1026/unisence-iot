package com.unisence.iot.message;

import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.message.type.NodeType;

import java.util.Map;

/**
 * 设备创建上报（线上 {@code mt=device_create}）。
 *
 * <p>只允许创建普通产品设备；{@code productKey + deviceCode} 幂等，冲突字段不得静默覆盖 ——
 * 后者是消费端保存事务的职责，本 record 只保证结构合法。
 *
 * @param deviceName  可空；展示名，最长 100 字符（{@code us_iot_device.device_name varchar(100)}）
 * @param nodeType    必填
 * @param gatewayCode 子设备必填、其余类型必须为 null（错误码 4110）
 * @param formData    受产品动态表单约束；可空，构造后为不可变空 map
 */
public record DeviceCreateMessage(
    int schemaVersion,
    String msgId,
    long occurredAt,
    String source,
    String productKey,
    String deviceCode,
    String deviceName,
    NodeType nodeType,
    String gatewayCode,
    Map<String, Object> formData) implements DeviceMessage {

    public static final int MAX_DEVICE_NAME_CHARS = 100;

    public DeviceCreateMessage {
        MessageFields.requireEnvelope(schemaVersion, msgId, occurredAt, source);
        MessageFields.requireDevice(productKey, deviceCode);
        if (nodeType == null) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                "nodeType", "不可为空");
        }
        if (deviceName != null && deviceName.length() > MAX_DEVICE_NAME_CHARS) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_TOO_LONG,
                                                "deviceName", deviceName.length() + " > " + MAX_DEVICE_NAME_CHARS);
        }
        if (nodeType.requiresGatewayCode() && (gatewayCode == null || gatewayCode.isBlank())) {
            throw new MessageStructureException(MessageErrorCode.GATEWAY_CODE_INCONSISTENT,
                                                "gatewayCode", "子设备必须携带 gatewayCode");
        }
        if (!nodeType.requiresGatewayCode() && gatewayCode != null) {
            throw new MessageStructureException(MessageErrorCode.GATEWAY_CODE_INCONSISTENT,
                                                "gatewayCode", nodeType + " 不得携带 gatewayCode");
        }
        if (gatewayCode != null) {
            MessageFields.requireText(gatewayCode, DeviceMessage.MAX_DEVICE_CODE_CHARS, "gatewayCode");
        }
        // 防御性拷贝：record 组件是引用，不拷贝则调用方仍能改写已构造消息的内容
        formData = formData == null ? Map.of() : Map.copyOf(formData);
    }

    @Override
    public MessageType messageType() {
        return MessageType.DEVICE_CREATE;
    }
}

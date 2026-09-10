package com.unisence.iot.message;

import com.unisence.iot.message.type.MessageType;

import java.util.Map;

/**
 * 服务心跳（线上 {@code mt=service_heartbeat}）。
 *
 * <p><b>不是设备消息</b>，故不实现 {@link DeviceMessage}：它没有 productKey/deviceCode。
 * 合进设备层次就必须让那两个字段可空，把「这条消息到底有没有设备」的判断永久留成运行期检查。
 *
 * @param serviceName 必填；服务名，最长 60 字符
 * @param instanceId  必填；实例 ID，最长 60 字符。租约键为服务名 + 实例 ID
 * @param startedAt   必填；实例启动时间 epoch millis，用于识别重启
 * @param version     可空
 * @param address     可空
 * @param metrics     可空；构造后为不可变空 map
 */
public record ServiceHeartbeatMessage(
    int schemaVersion,
    String msgId,
    long occurredAt,
    String source,
    String serviceName,
    String instanceId,
    long startedAt,
    String version,
    String address,
    Map<String, Object> metrics) implements IotMessage {

    public static final int MAX_SERVICE_NAME_CHARS = 60;
    public static final int MAX_INSTANCE_ID_CHARS = 60;

    public ServiceHeartbeatMessage {
        MessageFields.requireEnvelope(schemaVersion, msgId, occurredAt, source);
        MessageFields.requireText(serviceName, MAX_SERVICE_NAME_CHARS, "serviceName");
        MessageFields.requireText(instanceId, MAX_INSTANCE_ID_CHARS, "instanceId");
        if (startedAt <= 0) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                "startedAt", "必须为正 epoch millis，实际 " + startedAt);
        }
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    @Override
    public MessageType messageType() {
        return MessageType.SERVICE_HEARTBEAT;
    }

    /**
     * 分区键 {@code service.名称.实例}，与设备消息的 {@code productKey.deviceCode} 键空间天然不冲突。
     */
    @Override
    public String partitionKey() {
        return "service." + serviceName + '.' + instanceId;
    }
}

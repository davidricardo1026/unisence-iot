package com.unisence.iot.message;

import com.unisence.iot.message.type.HeartbeatStatus;
import com.unisence.iot.message.type.MessageType;

import java.util.Map;
import java.util.Set;

/**
 * 设备心跳（线上 {@code mt=device_heartbeat}）。
 *
 * <p>metrics 只接受白名单诊断字段：心跳频率高、不进物模型校验，
 * 若放开任意键就等于开了一条绕过物模型的遥测旁路，属性上报的类型约束会被整体架空。
 *
 * @param status  可空；缺省视为 {@link HeartbeatStatus#ONLINE}，取 {@link #statusOrDefault()}
 * @param metrics 可空；键必须落在 {@link #ALLOWED_METRIC_KEYS} 内（错误码 4109）
 */
public record DeviceHeartbeatMessage(
    int schemaVersion,
    String msgId,
    long occurredAt,
    String source,
    String productKey,
    String deviceCode,
    HeartbeatStatus status,
    Map<String, Object> metrics) implements DeviceMessage {

    /**
     * 诊断字段白名单。键不在其中的整条拒绝，不做静默丢弃 —— 静默会让驱动一直以为数据已上报。
     */
    public static final Set<String> ALLOWED_METRIC_KEYS = Set.of(
        "rssi", "snr", "battery", "uptimeMillis", "memUsedRatio", "cpuLoad", "firmwareVersion");

    public DeviceHeartbeatMessage {
        MessageFields.requireEnvelope(schemaVersion, msgId, occurredAt, source);
        MessageFields.requireDevice(productKey, deviceCode);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        for (String key : metrics.keySet()) {
            if (!ALLOWED_METRIC_KEYS.contains(key)) {
                throw new MessageStructureException(MessageErrorCode.HEARTBEAT_METRIC_NOT_ALLOWED,
                                                    "metrics." + key, "不在白名单 " + ALLOWED_METRIC_KEYS + " 内");
            }
        }
    }

    /**
     * 未上报 status 时按 {@link HeartbeatStatus#ONLINE} 处理。
     */
    public HeartbeatStatus statusOrDefault() {
        return status == null ? HeartbeatStatus.ONLINE : status;
    }

    @Override
    public MessageType messageType() {
        return MessageType.DEVICE_HEARTBEAT;
    }
}

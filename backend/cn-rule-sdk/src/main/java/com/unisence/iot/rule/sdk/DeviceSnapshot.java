package com.unisence.iot.rule.sdk;

import java.util.Map;

/**
 * 设备只读快照，对齐 {@code us_iot_device}。
 *
 * <p><b>安全红线</b>：{@code formData} 必须是 {@code device_form_data} 中<b>剔除</b>产品
 * {@code device_form_schema} 标记 {@code sensitive} 的字段之后的子集，而不是脱敏后保留。
 * 脚本输出会写进 Kafka，任何进入上下文的敏感值都等价于明文外泄，
 * 加密信封（{@code enc:v*:}）绝不允许出现在这里。
 *
 * @param gatewayCode 直连/网关为 null，子设备为所属网关的 device_code
 * @param nodeType    1-直连, 2-网关, 3-子设备
 * @param status      0-未激活, 1-在线, 2-离线, 3-未知（驱动失联，平台无法判断设备死活）
 */
public record DeviceSnapshot(
    long deviceId,
    String deviceCode,
    String deviceName,
    String gatewayCode,
    int nodeType,
    int status,
    Double longitude,
    Double latitude,
    Map<String, Object> formData) {

    public DeviceSnapshot {
        formData = formData == null ? Map.of() : Map.copyOf(formData);
    }

    public boolean online() {
        return status == 1;
    }

    public boolean isSubDevice() {
        return nodeType == 3;
    }

    /**
     * 表单字段未填或已因 sensitive 被剔除时返回 null。
     */
    public Object form(String field) {
        return formData.get(field);
    }
}

package com.unisence.iot.metadata;

import java.util.Map;

/**
 * 单台设备的<b>非敏感</b>运行投影（metadata-sync-bus.md §6.4）。
 *
 * <p>这是根外有界缓存与 Redis L2 里存放的值。字段选择遵循一条硬规则：
 * <b>敏感表单、连接密钥、加密信封、管理详情永不进入</b> —— L2 在 Redis 里、
 * 规则脚本能读到它，任何一条泄露路径都不可接受。
 *
 * <p>{@code status / last_online_at / activated_at} 同样不在此处：它们跟着上报频率高频跳变，
 * 放进「元数据缓存」会让每次状态变化都击穿缓存。当前在线态的权威源是 Redis 运行态结构。
 *
 * @param ruleVisibleFormData 已剔除敏感项、递归冻结的表单值；规则脚本可读
 */
public record DeviceRuntimeMeta(
    long deviceId,
    long productId,
    DeviceRef ref,
    String deviceName,
    Long gatewayId,
    String gatewayCode,
    int nodeType,
    Double longitude,
    Double latitude,
    Map<String, Object> ruleVisibleFormData) {

    public DeviceRuntimeMeta {
        if (ref == null) {
            throw new IllegalArgumentException("DeviceRef 不能为空: deviceId=" + deviceId);
        }
        ruleVisibleFormData = ruleVisibleFormData == null ? Map.of() : ruleVisibleFormData;
    }
}

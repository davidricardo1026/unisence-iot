package com.unisence.iot.engine.online;

/**
 * 一次待执行的续租（device-online-state-design.md §10.2）。
 *
 * @param deviceKey  {@code productKey.deviceCode}
 * @param ownerId    {@code serviceName:instanceId}，即最后上报该设备的驱动实例
 * @param expireAtMs <b>平台接收时间</b> + ttl。禁用报文 {@code occurredAt}：设备时钟漂移会让租约
 *                   永不过期或立即过期（§5.1）
 */
public record LeaseRenewal(String deviceKey, String ownerId, long expireAtMs) {
}

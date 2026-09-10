package com.unisence.iot.engine.repository;

import com.unisence.iot.engine.online.DeviceOnlineState;

/**
 * 一行待写的设备状态（device-online-state-design.md §7.1、§10.6）。
 *
 * <p>用 {@code deviceId} 主键定位而不是唯一键 {@code (product_id, device_code)}：
 * drainer 已经通过 {@code DeviceMetadataCache}（L1 → 分桶 L2 → MySQL 批量回源）
 * 把 stream 里的 {@code productKey + deviceCode} 批量解析成经双版本校验的 {@code deviceId}，
 * 主键定位更短也更稳。
 *
 * @param streamMs  Redis stream id 的毫秒部分，落 {@code status_event_ms}
 * @param streamSeq Redis stream id 的序号部分，落 {@code status_event_seq}
 */
public record DeviceStatusUpdate(
    long deviceId,
    DeviceOnlineState target,
    long changedAtMs,
    long streamMs,
    long streamSeq) {
}

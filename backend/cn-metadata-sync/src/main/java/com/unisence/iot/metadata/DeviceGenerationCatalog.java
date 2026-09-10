package com.unisence.iot.metadata;

import java.util.OptionalInt;

/**
 * 设备存在性与版本代际的紧凑目录（metadata-sync-bus.md §6.5）。
 *
 * <p>这是「百万设备不进每个 JVM」的关键取舍：根快照里<b>只</b>保留
 * {@code deviceId → rowVersion} 的原始数值与一个存在性 Bloom，完整的
 * {@link DeviceRuntimeMeta} 投影留在根外的有界缓存里，按热点而非总量占用内存。
 */
public interface DeviceGenerationCatalog {

    /**
     * 有效设备返回当前 {@code us_iot_device.version}；不存在或已删除返回空。
     *
     * <p>这个版本是设备缓存条目双版本校验的第一个版本：条目里记的版本与这里不一致，
     * 即视为 miss —— <b>不允许用 TTL 去猜新旧</b>。
     */
    OptionalInt version(long deviceId);

    /**
     * {@code false} 表示确定不存在；{@code true} 只表示可能存在，必须继续查 L1/L2/DB。
     */
    boolean mightContain(DeviceRef ref);

    /**
     * 已登记的设备数（base + delta）。
     */
    int size();

    /**
     * 供实例状态与容量核算上报的估算字节数。
     */
    long estimatedBytes();
}

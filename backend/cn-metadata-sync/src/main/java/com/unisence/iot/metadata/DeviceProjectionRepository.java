package com.unisence.iot.metadata;

import java.util.Map;
import java.util.Set;

/**
 * 设备投影的 MySQL 回源（metadata-sync-bus.md §13.3）。
 *
 * <p>返回值<b>尚未进入缓存</b>：调用方必须用最新根再校验一次双版本，
 * 因为回源期间可能已经有新的根被安装。
 */
public interface DeviceProjectionRepository {

    /**
     * 按 {@code productId} 分组批量查询。
     *
     * <p><b>禁止逐 {@link DeviceRef} 查询</b>：一个 poll 批次里几百台冷设备各查一次，
     * 就是把遥测吞吐直接打到关系库上 —— 那正是本设计要消除的东西。
     *
     * @param expectedRoot 构造投影时所依据的根；用于解析 productKey 与表单 schema
     */
    Map<DeviceRef, DeviceCacheEnvelope> loadBatch(Set<DeviceRef> refs, EngineMetadataSnapshot expectedRoot);
}

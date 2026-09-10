package com.unisence.iot.metadata;

import java.util.Map;
import java.util.Set;

/**
 * 一次批量建档的结果（engine-hotpath-optimization.md §10.7）。
 *
 * @param deviceIdsByRef 本批全部设备的 deviceId —— <b>含本次之前就已存在者</b>，
 *                       调用方据此清负缓存与校验可见性
 * @param insertedRefs   本次<b>真正新建</b>的设备。只有它们写了索引与变更目录，
 *                       因此也只有它们的可见性依赖本次收敛
 * @param commitSeq      本事务分配的水位；<b>全部新建设备共享同一个值</b>。
 *                       无新建时为 {@code null}，此时调用方不必推进水位、不必等待收敛
 * @param rejected       预校验被拒者及原因（产品不存在、网关缺失、表单不合法）。
 *                       <b>它们从未进入事务</b> —— 一条脏数据不该让整批回滚，
 *                       调用方逐条投 DLQ
 */
public record DeviceCreateBatchCommit(Map<DeviceRef, Long> deviceIdsByRef,
                                      Set<DeviceRef> insertedRefs,
                                      Long commitSeq,
                                      Map<DeviceRef, String> rejected) {

    public DeviceCreateBatchCommit {
        deviceIdsByRef = Map.copyOf(deviceIdsByRef);
        insertedRefs = Set.copyOf(insertedRefs);
        rejected = Map.copyOf(rejected);
    }

    public static DeviceCreateBatchCommit rejectedOnly(Map<DeviceRef, String> rejected) {
        return new DeviceCreateBatchCommit(Map.of(), Set.of(), null, rejected);
    }

    public boolean hasInserted() {
        return !insertedRefs.isEmpty();
    }
}

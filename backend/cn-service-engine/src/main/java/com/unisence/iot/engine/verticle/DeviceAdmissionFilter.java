package com.unisence.iot.engine.verticle;

import com.unisence.iot.metadata.*;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 设备存在性准入（metadata-sync-bus.md §6.7bis）。
 *
 * <p>平台的全部上行信息都以设备为主体，因此「设备是否存在」是数据面的<b>准入条件</b>而非可选校验。
 * 三种判定结果必须严格区分：
 *
 * <ul>
 *   <li><b>存在</b> —— 出现在返回集合里，调用方正常续租、校验物模型、落库；</li>
 *   <li><b>确认不存在</b> —— 不在返回集合里，调用方记一条 {@code warn} 后丢弃；</li>
 *   <li><b>暂时无法判定</b> —— 抛出异常，调用方<b>不得丢弃</b>，由 poll 循环回退 offset 整批重放。</li>
 * </ul>
 *
 * <p>第二与第三种的区分是本类存在的全部理由：{@link DeviceMetadataCache#getAll} 的空结果
 * 同时对应「设备不存在」与「本实例尚未收敛」，把后者当成前者就是用一条 {@code warn}
 * 静默丢掉合法数据，且不可恢复。因此<b>丢弃的唯一合法依据</b>是
 * {@link UnknownDeviceRepairCoordinator#repairOrNull} 追平权威水位后仍返回 {@code null}。
 */
@Slf4j
public final class DeviceAdmissionFilter {

    private final DeviceMetadataCache deviceCache;
    private final UnknownDeviceRepairCoordinator unknownDeviceRepair;
    private final MetadataSyncService syncService;

    public DeviceAdmissionFilter(DeviceMetadataCache deviceCache,
                                 UnknownDeviceRepairCoordinator unknownDeviceRepair,
                                 MetadataSyncService syncService) {
        this.deviceCache = deviceCache;
        this.unknownDeviceRepair = unknownDeviceRepair;
        this.syncService = syncService;
    }

    /**
     * 批量判定一个 poll 批次的设备准入。
     *
     * <p>先一次 {@link DeviceMetadataCache#getAll} 解决绝大多数（稳态下全部命中 L1，零网络），
     * 只有 miss 才逐条走修复流程 —— 逐条 {@code get()} 会把一次 pipeline 退化成几百次往返。
     *
     * @param refs 本批出现的全部设备引用；允许为空
     * @return 确认存在的设备；确认不存在者不在其中
     * @throws IllegalStateException                                         根快照尚未就绪，本批应重放
     * @throws UnknownDeviceRepairCoordinator.DeviceRepairRetryableException 暂时无法判定（依赖失败或等待超时）—— 调用方必须整批重放，<b>禁止据此丢弃</b>
     */
    public Set<DeviceRef> admit(Set<DeviceRef> refs) {
        if (refs.isEmpty()) {
            return Set.of();
        }
        EngineMetadataSnapshot root = syncService.current();
        if (root == null) {
            throw new IllegalStateException("根快照尚未就绪，本批稍后重放");
        }
        Map<DeviceRef, DeviceRuntimeMeta> resolved = deviceCache.getAll(refs, root);
        if (resolved.size() == refs.size()) {
            // getAll 的键必为 refs 子集，数量相等即全部命中
            return refs;
        }

        Set<DeviceRef> admitted = new LinkedHashSet<>(resolved.keySet());
        Set<DeviceRef> missing = new LinkedHashSet<>();
        for (DeviceRef ref : refs) {
            if (!admitted.contains(ref)) {
                missing.add(ref);
            }
        }
        // 未命中不足以下结论：可能只是本实例落后于权威水位。
        // 交给修复协调器 head-probe + MySQL 权威回源，只有它没返回该设备才是可下的结论。
        //
        // **必须用批量入口**（metadata-sync-bus.md §6.8 修订）：逐台 repairOrNull 会把
        // 一次批量 DB 回源退化成 N 次往返，而一个 poll 批里可能有几百台未知设备。
        // 抛 DeviceRepairRetryableException 时刻意不捕获 —— 整批重放远好过误丢数据。
        admitted.addAll(unknownDeviceRepair.repairAll(missing).keySet());
        return admitted;
    }
}

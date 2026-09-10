package com.unisence.iot.metadata;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 未知设备的确认与修复（metadata-sync-bus.md §6.8）。
 *
 * <p>要解决的是一个真实的竞态：设备刚在 MySQL 提交，提示还没到本实例，它的第一条消息就上来了。
 * 此时本地 Bloom 说「不存在」—— 但这只是本实例落后，不是设备真的不存在。
 * 直接判未知会让新建设备的首条消息无谓进 DLQ。
 *
 * <p>因此在下「不存在」这个结论之前，必须先追平水位再用新根重试。三层封顶保证这个过程不会
 * 变成新的放大器：
 * <ul>
 *   <li><b>stripe 锁</b> —— 同一台设备的并发消息只有一个去修复，其余等待结果；</li>
 *   <li><b>全局 head-probe single-flight + 新鲜度限频</b> —— 一批未知设备最多触发一次 head 查询，
 *       而不是每台设备各查一次；</li>
 *   <li><b>Semaphore</b> —— 全实例并发修复数封顶。</li>
 * </ul>
 *
 * <p><b>依赖失败或等待超时绝不写负缓存</b>：那会把一次外部故障固化成「这台设备不存在」。
 */
@Slf4j
public final class UnknownDeviceRepairCoordinator {

    private final MetadataSyncService syncService;
    private final MetadataControlRepository controlRepository;
    private final DeviceMetadataCache deviceCache;
    private final MetadataProperties properties;

    /**
     * 负缓存：确认不存在的设备，避免每条消息都重跑一遍修复流程。
     */
    private final Cache<DeviceRef, NegativeDeviceMarker> negativeCache;
    /**
     * 固定条数的 stripe 锁：按 stableHash 取模，同设备必然落同一把锁。
     */
    private final ReentrantLock[] stripes;
    private final Semaphore repairPermits;

    /**
     * 全局 head 探测的 single-flight 与新鲜度控制。
     */
    private final AtomicBoolean headProbeInFlight = new AtomicBoolean();
    private final AtomicLong lastHeadProbeAt = new AtomicLong();

    public UnknownDeviceRepairCoordinator(MetadataSyncService syncService,
                                          MetadataControlRepository controlRepository,
                                          DeviceMetadataCache deviceCache,
                                          MetadataProperties properties) {
        this.syncService = syncService;
        this.controlRepository = controlRepository;
        this.deviceCache = deviceCache;
        this.properties = properties;
        this.negativeCache = Caffeine.newBuilder()
            .maximumSize(properties.unknownDeviceCacheMaxEntries())
            .expireAfterWrite(properties.unknownDeviceCacheTtlMs(), TimeUnit.MILLISECONDS)
            .build();
        this.stripes = new ReentrantLock[properties.unknownDeviceRepairStripes()];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
        this.repairPermits = new Semaphore(properties.unknownDeviceRepairMaxConcurrency());
    }

    /**
     * <b>批量</b>确认一批设备是否真的不存在（§6.8 修订：调用方必须批量）。
     *
     * <p><b>为什么必须有这个入口</b>：修复路径现在靠 MySQL 回源给出权威答案，
     * 而 {@code DeviceMetadataCache.getAll} 的回源本就是批量的。
     * 逐台调用 {@link #repairOrNull} 会把「一次批量回源」退化成「N 次往返」——
     * 这正是本仓多处注释反复强调的反模式。
     *
     * <p>head 探测是全局 single-flight 且带新鲜度限频，因此一批设备最多触发一次 MySQL head 查询。
     *
     * @param refs 一批未在缓存中解析到的设备
     * @return 其中<b>确认存在</b>的设备投影；确认不存在者不在返回集合中
     * @throws DeviceRepairRetryableException 依赖失败 —— 调用方必须整批重放，禁止据此丢弃
     */
    public Map<DeviceRef, DeviceRuntimeMeta> repairAll(Set<DeviceRef> refs) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        EngineMetadataSnapshot root = syncService.current();
        if (root == null) {
            throw new DeviceRepairRetryableException("根快照尚未就绪");
        }
        long now = System.currentTimeMillis();
        Set<DeviceRef> candidates = new LinkedHashSet<>(refs.size());
        for (DeviceRef ref : refs) {
            NegativeDeviceMarker marker = negativeCache.getIfPresent(ref);
            // 负缓存仍新鲜：已确认不存在，不必再查
            if (marker == null || marker.isStale(root.appliedHead(), now,
                                                 properties.unknownDeviceCacheTtlMs())) {
                candidates.add(ref);
            }
        }
        if (candidates.isEmpty()) {
            return Map.of();
        }

        boolean acquired;
        try {
            acquired = repairPermits.tryAcquire(
                properties.unknownDeviceReconcileTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceRepairRetryableException("等待修复许可被中断");
        }
        if (!acquired) {
            throw new DeviceRepairRetryableException("修复并发已满: size=" + candidates.size());
        }
        try {
            // 一次 head 探测覆盖整批；随后一次批量回源给出权威答案（不等收敛，见 probeThenRetry 的说明）
            probeHeadOnce();
            EngineMetadataSnapshot fresh = syncService.current();
            Map<DeviceRef, DeviceRuntimeMeta> resolved = deviceCache.getAll(candidates, fresh);
            long checkedAt = System.currentTimeMillis();
            for (DeviceRef ref : candidates) {
                if (!resolved.containsKey(ref)) {
                    // 追平权威水位后仍解析不到：这才是可以下的结论
                    negativeCache.put(ref, new NegativeDeviceMarker(fresh.appliedHead(), checkedAt));
                }
            }
            return resolved;
        } finally {
            repairPermits.release();
        }
    }

    /**
     * 确认设备是否真的不存在，必要时先追平水位。
     *
     * <p><b>单台入口，仅供无法批量的调用方使用</b>（如 rule-stream 的 `RuleProcessor`，
     * 它按记录逐条处理）。能批量的调用方一律用 {@link #repairAll}。
     *
     * @return 修复成功返回投影；<b>确认不存在返回 {@code null}</b>
     * @throws DeviceRepairRetryableException 依赖失败或等待超时 —— 调用方应重试而不是判未知
     */
    public DeviceRuntimeMeta repairOrNull(DeviceRef ref) {
        EngineMetadataSnapshot root = syncService.current();
        if (root == null) {
            throw new DeviceRepairRetryableException("根快照尚未就绪: " + ref.deviceKey());
        }
        NegativeDeviceMarker marker = negativeCache.getIfPresent(ref);
        if (marker != null && !marker.isStale(root.appliedHead(), System.currentTimeMillis(),
                                              properties.unknownDeviceCacheTtlMs())) {
            return null;
        }

        ReentrantLock stripe = stripes[(int) (ref.stableHash() % stripes.length)];
        boolean locked;
        try {
            locked = stripe.tryLock(properties.unknownDeviceReconcileTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceRepairRetryableException("等待修复锁被中断: " + ref.deviceKey());
        }
        if (!locked) {
            throw new DeviceRepairRetryableException("等待同设备修复超时: " + ref.deviceKey());
        }
        try {
            // 拿到锁后重查一次：前一个持锁者可能已经修好了
            DeviceRuntimeMeta resolved = deviceCache.get(ref, syncService.current());
            if (resolved != null) {
                return resolved;
            }
            return probeThenRetry(ref);
        } finally {
            stripe.unlock();
        }
    }

    /**
     * 追平水位后用新根重试；仍然找不到才认定不存在。
     */
    private DeviceRuntimeMeta probeThenRetry(DeviceRef ref) {
        boolean acquired;
        try {
            acquired = repairPermits.tryAcquire(
                properties.unknownDeviceReconcileTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceRepairRetryableException("等待修复许可被中断: " + ref.deviceKey());
        }
        if (!acquired) {
            throw new DeviceRepairRetryableException("修复并发已满: " + ref.deviceKey());
        }
        try {
            probeHeadOnce();
            // 【2026-08-09 A5 根治】此处**刻意不再 awaitConvergence()**。
            //
            // probeHeadOnce() 已把 desiredHead 抬到权威值，于是 §6.5bis 的判据
            // `root.appliedHead() >= desiredHead` 必然为 false ——
            // 下面这行 deviceCache.get 会**自动走 DB 回源**并给出权威答案。
            //
            // 原实现在这里等收敛，等于把「单台设备是否存在」（O(1)）绑到
            // 「整个元数据是否收敛完」（O(device-catalog-max-entries)）上：
            // 全量重建时实测 5001 台 22ms，外推 150 万台 6.6s > 超时 5s，
            // 两个本无关系的配置就此耦合（architecture-open-issues.md §A5）。
            // 等待收敛从来不是正确性所必需，只是为省一次点查 —— 这个交换不划算。
            EngineMetadataSnapshot root = syncService.current();
            DeviceRuntimeMeta resolved = deviceCache.get(ref, root);
            if (resolved != null) {
                return resolved;
            }
            // 追平之后仍然没有：这才是可以下的结论
            negativeCache.put(ref, new NegativeDeviceMarker(root.appliedHead(), System.currentTimeMillis()));
            return null;
        } finally {
            repairPermits.release();
        }
    }

    /**
     * 全局 head 探测：single-flight + 新鲜度限频。
     *
     * <p>一个 poll 批次里可能有几百台未知设备，各查一次 MySQL head 就是把关系库当缓存用。
     * 这里保证<b>并发恒为 1</b>，且在 {@code freshness} 窗口内只查一次 —— 后来者直接复用刚拿到的结果。
     */
    private void probeHeadOnce() {
        long now = System.currentTimeMillis();
        if (now - lastHeadProbeAt.get() < properties.unknownDeviceHeadProbeFreshnessMs()) {
            return;
        }
        if (!headProbeInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            long committedHead = controlRepository.readCommittedHead();
            lastHeadProbeAt.set(System.currentTimeMillis());
            syncService.observeDesiredHead(committedHead, MetadataSyncTrigger.MYSQL_ANTI_ENTROPY);
        } catch (Exception e) {
            // MySQL 不可用：抛可重试异常，绝不写负缓存
            throw new DeviceRepairRetryableException("查询权威水位失败: " + e.getMessage());
        } finally {
            headProbeInFlight.set(false);
        }
    }

    /**
     * 设备创建成功后立即清掉负缓存，让后续消息不必等 TTL。
     */
    public void clearNegative(Set<DeviceRef> refs) {
        negativeCache.invalidateAll(refs);
    }

    /**
     * 可重试：依赖失败或等待超时，调用方应退避重试而不是判定设备不存在。
     */
    public static final class DeviceRepairRetryableException extends RuntimeException {
        public DeviceRepairRetryableException(String message) {
            super(message);
        }
    }
}

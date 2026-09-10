package com.unisence.iot.metadata;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 设备投影的三级缓存：Caffeine L1 → 分桶 Redis L2 → MySQL 批量回源（metadata-sync-bus.md §6.6、§6.7）。
 *
 * <p>整个结构的安全性来自一条规则：<b>版本不匹配等价于 miss</b>（{@link DeviceCacheEnvelope#isValidFor}）。
 * 因此 L1 的淘汰、L2 的丢写与乱序写、多线程重复填充全都只影响命中率，不可能返回过期数据 ——
 * 这也是为什么这里不需要任何跨级的失效广播或分布式锁。
 *
 * <p>L1 按<b>权重字节</b>而不是条目数封顶：设备表单大小差异可达两个数量级，
 * 按条目数限制会让「1 万条小表单」和「1 万条大表单」占用截然不同的堆，容量根本无法规划。
 */
@Slf4j
public final class DeviceMetadataCache {

    private final Cache<DeviceRef, DeviceCacheEnvelope> l1;
    private final DeviceL2Store l2;
    private final DeviceProjectionRepository repository;
    private final MetadataProperties properties;
    /**
     * 只用于判定「Bloom 的否定此刻是否权威」（§6.5bis），不参与任何加载逻辑。
     *
     * <p>注入方向为单向链 {@code UnknownDeviceRepairCoordinator → DeviceMetadataCache → MetadataSyncService}：
     * {@code MetadataSyncService} 不引用本类，因此不构成循环依赖。
     */
    private final MetadataSyncService syncService;
    /**
     * 回源并发封顶：Redis 故障时若放任每条消息各自查库，关系库会被瞬间打垮。
     */
    private final Semaphore dbFallbackPermits;

    /**
     * 可选观测回调；默认 NOOP。
     */
    private volatile MetadataObserver observer = MetadataObserver.NOOP;

    private final LongAdder lookups = new LongAdder();
    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder dbFallbacks = new LongAdder();

    public DeviceMetadataCache(DeviceL2Store l2,
                               DeviceProjectionRepository repository,
                               MetadataSyncService syncService,
                               MetadataProperties properties) {
        this.l2 = l2;
        this.repository = repository;
        this.syncService = syncService;
        this.properties = properties;
        this.dbFallbackPermits = new Semaphore(properties.deviceDbFallbackMaxConcurrency());
        this.l1 = Caffeine.newBuilder()
            .maximumWeight(properties.deviceL1MaxWeightBytes())
            .weigher((DeviceRef key, DeviceCacheEnvelope value) -> estimateBytes(value))
            .expireAfterAccess(properties.deviceL1ExpireAfterAccessSeconds(), TimeUnit.SECONDS)
            // 刻意不用 refreshAfterWrite：它在刷新期间会继续返回旧值，
            // 而这里「旧」的判定权只在双版本校验手里，不能交给时间
            .recordStats()
            .build();
    }

    /**
     * 单条读取（规则 topology 的逐记录入口）。
     *
     * <p>L1 命中直接返回；双版本校验与 miss 加载均与 {@link #getAll} 共用。
     *
     * @return 设备不存在或本根无法解析时返回 {@code null}
     */
    public DeviceRuntimeMeta get(DeviceRef ref, EngineMetadataSnapshot root) {
        Objects.requireNonNull(ref, "ref");
        lookups.increment();
        DeviceRuntimeMeta value = validL1(ref, l1.getIfPresent(ref), root);
        if (value != null) return value;
        return resolveMisses(new LinkedHashMap<>(), new LinkedHashSet<>(Set.of(ref)), root).get(ref);
    }

    /**
     * Kafka poll 批次使用：合并 L2 pipeline 与 MySQL 查询。
     *
     * <p>严格按 §6.7 的七步执行，每一步都<b>批量</b>进行 —— 逐条处理会把「一次 pipeline」
     * 退化成「几百次往返」。
     */
    public Map<DeviceRef, DeviceRuntimeMeta> getAll(Set<DeviceRef> refs, EngineMetadataSnapshot root) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        lookups.add(refs.size());
        Map<DeviceRef, DeviceRuntimeMeta> resolved = new LinkedHashMap<>(refs.size());
        Set<DeviceRef> unresolved = new LinkedHashSet<>(refs);

        // ① L1 批量取 + 逐条双版本校验
        Map<DeviceRef, DeviceCacheEnvelope> fromL1 = l1.getAllPresent(unresolved);
        for (var entry : fromL1.entrySet()) {
            DeviceRuntimeMeta value = validL1(entry.getKey(), entry.getValue(), root);
            if (value != null) {
                resolved.put(entry.getKey(), value);
                unresolved.remove(entry.getKey());
            }
        }
        return resolveMisses(resolved, unresolved, root);
    }

    private DeviceRuntimeMeta validL1(DeviceRef ref, DeviceCacheEnvelope envelope, EngineMetadataSnapshot root) {
        if (envelope == null) return null;
        if (envelope.isValidFor(ref, root)) {
            l1Hits.increment();
            return envelope.value();
        }
        // 版本已变：立即失效，不等 TTL。
        l1.invalidate(ref);
        return null;
    }

    private Map<DeviceRef, DeviceRuntimeMeta> resolveMisses(Map<DeviceRef, DeviceRuntimeMeta> resolved,
                                                            Set<DeviceRef> unresolved,
                                                            EngineMetadataSnapshot root) {
        if (unresolved.isEmpty()) {
            return resolved;
        }

        // ② Bloom 明确否定的直接剔除，省掉一次 L2 与一次 DB。
        //
        // 但这个剔除只在本代际目录已覆盖全部已提交变更时才成立（§6.5bis）：
        // Bloom 属于 root，只覆盖到 root.appliedHead()。设备若已在 MySQL 提交而水位尚未应用，
        // 查询必然返回 false —— 那个 false 的含义是「本代际里还没有」，不是「设备不存在」。
        // 水位落后时必须继续走 L2/MySQL 由权威数据源裁决，否则调用方拿到的空结果
        // 会把「未收敛」与「不存在」混为一谈，而 getAll 的返回类型无法表达这个差别。
        if (bloomNegativeIsAuthoritative(root)) {
            Set<DeviceRef> maybeExisting = new LinkedHashSet<>();
            for (DeviceRef ref : unresolved) {
                if (root.deviceCatalog().mightContain(ref)) {
                    maybeExisting.add(ref);
                }
            }
            unresolved = maybeExisting;
            if (unresolved.isEmpty()) {
                return resolved;
            }
        }

        // ③ L2：按桶 pipeline 读取，命中同样逐条双版本校验
        Map<DeviceRef, DeviceCacheEnvelope> fromL2 = l2.getAll(unresolved);
        Map<DeviceRef, DeviceCacheEnvelope> promote = new HashMap<>();
        for (var entry : fromL2.entrySet()) {
            if (entry.getValue().isValidFor(entry.getKey(), root)) {
                resolved.put(entry.getKey(), entry.getValue().value());
                unresolved.remove(entry.getKey());
                promote.put(entry.getKey(), entry.getValue());
                l2Hits.increment();
            }
        }
        if (!promote.isEmpty()) {
            l1.putAll(promote);
        }
        if (unresolved.isEmpty()) {
            return resolved;
        }

        // ④ MySQL 批量回源
        resolved.putAll(loadFromDatabase(unresolved, root));
        return resolved;
    }

    /**
     * 本批所用根的目录是否已覆盖全部已知提交（§6.5bis）。
     *
     * <p>比较的是<b>本批固定的 {@code root}</b> 而不是 {@code syncService.appliedHead()}：
     * 调用方可能持有一个较旧的根，此时即便实例整体已追平，这个根的目录仍然是旧的。
     *
     * <p>残余竞态由 §6.8 修复流程覆盖：提示丢失时 {@code desiredHead} 自身可能偏低，
     * 本判据会误认为已追平；那一层通过查询 MySQL <b>权威</b> head 兜住。
     * 两者是分层关系 —— 本判据消除稳态下的系统性混淆且成本为零，
     * 修复流程兜住尾部情况但要付一次 MySQL 查询与有界等待。
     */
    private boolean bloomNegativeIsAuthoritative(EngineMetadataSnapshot root) {
        return root.appliedHead() >= syncService.desiredHead();
    }

    /**
     * 有界回源并写回两级缓存。
     *
     * <p>写回前<b>重新读取当前根</b>再校验一次双版本：回源期间可能已经安装了新根，
     * 此时候选值已经是旧代际，写进缓存就是在制造一个必然被拒绝的条目。
     */
    /**
     * 装配期注入观测回调。
     */
    public void observer(MetadataObserver observer) {
        this.observer = observer == null ? MetadataObserver.NOOP : observer;
    }

    private Map<DeviceRef, DeviceRuntimeMeta> loadFromDatabase(Set<DeviceRef> refs, EngineMetadataSnapshot root) {
        long fallbackStartedAt = System.nanoTime();
        boolean acquired;
        try {
            acquired = dbFallbackPermits.tryAcquire(properties.deviceLoadTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
        if (!acquired) {
            // 回源预算耗尽：抛出让调用方暂停对应分区并退避，绝不能把已知设备误判成不存在
            throw new DeviceLoadOverloadException(refs.size());
        }
        try {
            dbFallbacks.add(refs.size());
            Map<DeviceRef, DeviceCacheEnvelope> loaded = repository.loadBatch(refs, root);
            if (loaded.isEmpty()) {
                return Map.of();
            }
            Map<DeviceRef, DeviceRuntimeMeta> result = new LinkedHashMap<>(loaded.size());
            Map<DeviceRef, DeviceCacheEnvelope> writable = new LinkedHashMap<>(loaded.size());
            for (var entry : loaded.entrySet()) {
                result.put(entry.getKey(), entry.getValue().value());
                if (entry.getValue().isValidFor(entry.getKey(), root)
                    && estimateBytes(entry.getValue()) <= properties.deviceCacheEntryMaxBytes()) {
                    writable.put(entry.getKey(), entry.getValue());
                }
            }
            if (!writable.isEmpty()) {
                l1.putAll(writable);
                l2.putAll(writable);
            }
            return result;
        } finally {
            dbFallbackPermits.release();
            observer.metadataDbFallback(refs.size(), (System.nanoTime() - fallbackStartedAt) / 1_000_000L);
        }
    }

    /**
     * 设备静态变更后由收敛器调用，主动清掉两级条目 —— 不清也安全，只是会多一次校验失败。
     */
    public void invalidate(Set<DeviceRef> refs) {
        if (refs.isEmpty()) {
            return;
        }
        l1.invalidateAll(refs);
        l2.invalidateAll(refs);
    }

    /**
     * {@code device_create} 用：把创建时已校验的投影直接写入两级缓存。
     */
    public void put(DeviceRef ref, DeviceCacheEnvelope envelope, EngineMetadataSnapshot root) {
        if (!envelope.isValidFor(ref, root)) {
            log.warn("投影与当前根不匹配，拒绝写入缓存: ref={}", ref.deviceKey());
            return;
        }
        Map<DeviceRef, DeviceCacheEnvelope> single = Map.of(ref, envelope);
        l1.putAll(single);
        l2.putAll(single);
    }

    /**
     * 估算条目字节权重。
     *
     * <p>刻意用粗略估算而不是精确测量：{@code Instrumentation} 在生产不可用，
     * 而权重只用于「不要让少数大条目吃掉整个预算」这个相对判断，绝对精度没有意义。
     * 真实容量必须靠 JFR retained-heap 压测确认，这里的公式只是防止量级失控。
     */
    private static int estimateBytes(DeviceCacheEnvelope envelope) {
        DeviceRuntimeMeta value = envelope.value();
        int bytes = 160; // 对象头 + 固定标量字段的粗略摊销
        bytes += length(value.ref().productKey()) + length(value.ref().deviceCode());
        bytes += length(value.deviceName()) + length(value.gatewayCode());
        bytes += estimateMapBytes(value.ruleVisibleFormData(), 0);
        return bytes;
    }

    private static int estimateMapBytes(Map<String, Object> map, int depth) {
        if (map == null || map.isEmpty() || depth > 8) {
            return 0;
        }
        int bytes = 48;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            bytes += 48 + length(entry.getKey());
            bytes += estimateValueBytes(entry.getValue(), depth + 1);
        }
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static int estimateValueBytes(Object value, int depth) {
        if (value == null || depth > 8) {
            return 8;
        }
        if (value instanceof String text) {
            return length(text);
        }
        if (value instanceof Map<?, ?> map) {
            return estimateMapBytes((Map<String, Object>) map, depth);
        }
        if (value instanceof java.util.List<?> list) {
            int bytes = 32;
            for (Object item : list) {
                bytes += estimateValueBytes(item, depth + 1);
            }
            return bytes;
        }
        return 24;
    }

    private static int length(String text) {
        // UTF-16 两字节每字符，再加 String + byte[] 的对象开销
        return text == null ? 0 : 40 + text.length() * 2;
    }

    /**
     * 供实例状态上报的缓存统计。
     */
    public Stats stats() {
        long total = lookups.sum();
        var caffeineStats = l1.stats();
        return new Stats(
            total == 0 ? 0 : (double) l1Hits.sum() / total,
            total == 0 ? 0 : (double) l2Hits.sum() / total,
            total == 0 ? 0 : (double) dbFallbacks.sum() / total,
            estimateWeight(),
            caffeineStats.evictionCount());
    }

    private long estimateWeight() {
        long weight = 0;
        for (DeviceCacheEnvelope envelope : l1.asMap().values()) {
            weight += estimateBytes(envelope);
        }
        return weight;
    }

    /**
     * 缓存观测值；命中率是趋势指标，不要求精确。
     */
    public record Stats(double l1HitRate, double l2HitRate, double dbFallbackRate,
                        long l1WeightBytes, long evictionCount) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0);
    }

    /**
     * 回源预算耗尽。
     *
     * <p>调用方必须<b>暂停对应 Kafka 分区并退避</b>，恢复到低水位后再继续 ——
     * 既不能把已知设备误判成不存在（那会污染负缓存），也不能无界排队压垮 MySQL。
     */
    public static final class DeviceLoadOverloadException extends RuntimeException {
        public DeviceLoadOverloadException(int pending) {
            super("设备投影回源预算耗尽，待处理=" + pending);
        }
    }
}

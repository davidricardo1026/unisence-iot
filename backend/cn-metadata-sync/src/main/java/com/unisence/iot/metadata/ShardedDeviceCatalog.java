package com.unisence.iot.metadata;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalInt;

/**
 * {@link DeviceGenerationCatalog} 的分片原始数组实现（metadata-sync-bus.md §6.5）。
 *
 * <p><b>为什么不是 {@code HashMap<Long,Integer>}</b>：百万条目会产生百万个 {@code Long}
 * 与 {@code Integer} 装箱对象加上 {@code Node} 节点，光对象头就是几十 MB，还全部是 GC 可达的
 * 老年代存活对象。这里每台设备只占 {@code long}(8B) + {@code int}(4B) = 12 字节的连续数组空间，
 * 无对象头、无指针追逐，且对 CPU cache 友好。
 *
 * <p><b>为什么要分 1024 片</b>：目录是不可变的，单设备变更也要产生新实例。若整个百万元素数组
 * 一次性复制，一次改名就要拷 12 MB；分片后只需复制所在片的约 977 组数值，其余 1023 片
 * <b>结构共享</b>（直接传引用）。
 *
 * <p>片内按 {@code deviceId} 升序排列，查询用二分 —— 这也是能用原始数组的前提。
 */
public final class ShardedDeviceCatalog implements DeviceGenerationCatalog {

    private final long[][] deviceIds;
    private final int[][] rowVersions;
    private final DeviceExistenceFilter existenceFilter;
    private final int shardMask;
    private final int size;

    private ShardedDeviceCatalog(long[][] deviceIds, int[][] rowVersions,
                                 DeviceExistenceFilter existenceFilter, int size) {
        this.deviceIds = deviceIds;
        this.rowVersions = rowVersions;
        this.existenceFilter = existenceFilter;
        this.shardMask = deviceIds.length - 1;
        this.size = size;
    }

    /**
     * 引导完成前的空目录：Bloom 取 permissive，避免把真实设备误判成不存在。
     */
    public static ShardedDeviceCatalog empty(int shardCount) {
        requirePowerOfTwo(shardCount);
        long[][] ids = new long[shardCount][];
        int[][] versions = new int[shardCount][];
        Arrays.fill(ids, EMPTY_IDS);
        Arrays.fill(versions, EMPTY_VERSIONS);
        return new ShardedDeviceCatalog(ids, versions, DeviceExistenceFilter.permissive(), 0);
    }

    private static final long[] EMPTY_IDS = new long[0];
    private static final int[] EMPTY_VERSIONS = new int[0];

    @Override
    public OptionalInt version(long deviceId) {
        int shard = shardOf(deviceId);
        long[] ids = deviceIds[shard];
        int at = Arrays.binarySearch(ids, deviceId);
        return at < 0 ? OptionalInt.empty() : OptionalInt.of(rowVersions[shard][at]);
    }

    @Override
    public boolean mightContain(DeviceRef ref) {
        return existenceFilter.mightContain(ref);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public long estimatedBytes() {
        // 每条 8 + 4 字节，再加 Bloom 位图与每片数组头的粗略摊销
        return (long) size * (Long.BYTES + Integer.BYTES)
            + existenceFilter.estimatedBytes()
            + (long) deviceIds.length * 2 * 16;
    }

    public DeviceExistenceFilter existenceFilter() {
        return existenceFilter;
    }

    /**
     * 应用一批增量变更，返回新目录。
     *
     * <p><b>必须按 shard 聚合后每片只复制一次</b>：逐条 apply 会让一批 500 条变更把同一个片
     * 复制 500 遍，退化成 O(变更数 × 片长)。
     *
     * @param upserts   存活设备的 {@code deviceId → rowVersion}
     * @param removals  已删除设备的 deviceId
     * @param additions 新增设备的 ref，用于补进 Bloom delta（保证无假阴性）
     */
    public ShardedDeviceCatalog withChanges(Map<Long, Integer> upserts,
                                            Collection<Long> removals,
                                            Collection<DeviceRef> additions) {
        if (upserts.isEmpty() && removals.isEmpty()) {
            return additions == null || additions.isEmpty()
                ? this
                : new ShardedDeviceCatalog(deviceIds, rowVersions,
                                           existenceFilter.withAdditions(additions), size);
        }

        // 先按片分组，保证每片最多重建一次
        Map<Integer, java.util.TreeMap<Long, Integer>> byShard = new java.util.HashMap<>();
        for (Map.Entry<Long, Integer> entry : upserts.entrySet()) {
            byShard.computeIfAbsent(shardOf(entry.getKey()), k -> new java.util.TreeMap<>())
                .put(entry.getKey(), entry.getValue());
        }
        java.util.Map<Integer, java.util.Set<Long>> removalsByShard = new java.util.HashMap<>();
        for (Long deviceId : removals) {
            removalsByShard.computeIfAbsent(shardOf(deviceId), k -> new java.util.HashSet<>()).add(deviceId);
        }

        long[][] newIds = deviceIds.clone();
        int[][] newVersions = rowVersions.clone();
        int newSize = size;
        java.util.Set<Integer> touched = new java.util.HashSet<>(byShard.keySet());
        touched.addAll(removalsByShard.keySet());
        for (int shard : touched) {
            MergeResult merged = mergeShard(
                deviceIds[shard], rowVersions[shard],
                byShard.getOrDefault(shard, new java.util.TreeMap<>()),
                removalsByShard.getOrDefault(shard, java.util.Set.of()));
            newIds[shard] = merged.ids();
            newVersions[shard] = merged.versions();
            newSize += merged.ids().length - deviceIds[shard].length;
        }
        return new ShardedDeviceCatalog(
            newIds, newVersions, existenceFilter.withAdditions(additions), newSize);
    }

    private record MergeResult(long[] ids, int[] versions) {
    }

    /**
     * 有序归并：旧片与本次变更都按 deviceId 升序，一次线性扫描即可，无需排序。
     */
    private static MergeResult mergeShard(long[] oldIds, int[] oldVersions,
                                          java.util.TreeMap<Long, Integer> upserts,
                                          java.util.Set<Long> removals) {
        long[] ids = new long[oldIds.length + upserts.size()];
        int[] versions = new int[ids.length];
        int out = 0;
        int i = 0;
        var iterator = upserts.entrySet().iterator();
        Map.Entry<Long, Integer> pending = iterator.hasNext() ? iterator.next() : null;
        while (i < oldIds.length || pending != null) {
            if (pending == null || (i < oldIds.length && oldIds[i] < pending.getKey())) {
                if (!removals.contains(oldIds[i])) {
                    ids[out] = oldIds[i];
                    versions[out] = oldVersions[i];
                    out++;
                }
                i++;
            } else {
                if (i < oldIds.length && oldIds[i] == pending.getKey()) {
                    i++;
                }
                if (!removals.contains(pending.getKey())) {
                    ids[out] = pending.getKey();
                    versions[out] = pending.getValue();
                    out++;
                }
                pending = iterator.hasNext() ? iterator.next() : null;
            }
        }
        return new MergeResult(Arrays.copyOf(ids, out), Arrays.copyOf(versions, out));
    }

    private int shardOf(long deviceId) {
        return (int) (deviceId & shardMask);
    }

    private static void requirePowerOfTwo(int shardCount) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("shard 数必须是 2 的幂: " + shardCount);
        }
    }

    /**
     * 全量流式构建器（启动引导与低峰重建）。
     *
     * <p>按 {@code device_id} 游标分页喂入，构建器内部只累积原始数值，
     * <b>不保留任何 DTO 或完整投影</b> —— 百万条 DTO 会在构建期就把堆打满。
     */
    public static final class Builder {

        private final int shardCount;
        private final long[][] ids;
        private final int[][] versions;
        private final int[] counts;
        private final DeviceExistenceFilter.Builder bloom;
        private int total;

        public Builder(int shardCount, int expectedEntries, double falsePositiveProbability) {
            requirePowerOfTwo(shardCount);
            this.shardCount = shardCount;
            this.ids = new long[shardCount][];
            this.versions = new int[shardCount][];
            this.counts = new int[shardCount];
            int initial = Math.max(16, expectedEntries / shardCount);
            for (int i = 0; i < shardCount; i++) {
                ids[i] = new long[initial];
                versions[i] = new int[initial];
            }
            this.bloom = DeviceExistenceFilter.builder(expectedEntries, falsePositiveProbability);
        }

        /**
         * 必须按 {@code device_id} 升序喂入 —— 片内有序是二分查询成立的前提。
         */
        public void add(long deviceId, int rowVersion, DeviceRef ref) {
            int shard = (int) (deviceId & (shardCount - 1));
            if (counts[shard] == ids[shard].length) {
                int grown = ids[shard].length * 2;
                ids[shard] = Arrays.copyOf(ids[shard], grown);
                versions[shard] = Arrays.copyOf(versions[shard], grown);
            }
            ids[shard][counts[shard]] = deviceId;
            versions[shard][counts[shard]] = rowVersion;
            counts[shard]++;
            bloom.add(ref);
            total++;
        }

        public int size() {
            return total;
        }

        public ShardedDeviceCatalog build() {
            long[][] finalIds = new long[shardCount][];
            int[][] finalVersions = new int[shardCount][];
            for (int i = 0; i < shardCount; i++) {
                // 收缩到实际长度：按 2 倍扩容后最坏会浪费一半空间，百万级下不可忽略
                finalIds[i] = Arrays.copyOf(ids[i], counts[i]);
                finalVersions[i] = Arrays.copyOf(versions[i], counts[i]);
            }
            return new ShardedDeviceCatalog(finalIds, finalVersions, bloom.build(), total);
        }
    }
}

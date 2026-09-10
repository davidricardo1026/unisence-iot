package com.unisence.iot.metadata;

import java.util.Arrays;
import java.util.Collection;

/**
 * 设备存在性过滤器（metadata-sync-bus.md §6.5）。
 *
 * <p>由「不可变 base Bloom + 自上次重建以来的新增 delta」两部分组成，语义严格是单向的：
 * <ul>
 *   <li><b>明确不存在</b> → 可以直接拒绝，省掉一次 L2 与一次 DB 查询；</li>
 *   <li><b>可能存在</b> → 必须继续查 L1/L2/DB，不得据此下任何结论。</li>
 * </ul>
 *
 * <p><b>删除不从 base 擦除</b>。Bloom 的位是多个元素共享的，擦掉一个元素的位会连带把
 * 其它元素误判成「不存在」—— 那是<b>假阴性</b>，会让真实存在的设备被永久拒绝。
 * 保留删除设备的位只产生假阳性（多查一次库），代价可接受且安全。
 *
 * <p>新增写入 delta 精确集合，保证「新建设备立刻可见」，同样没有假阴性。
 *
 * <p>手写位图而不是引入 Guava：engine 依赖树目前只有 vertx-core + Netty + Kafka + Greptime，
 * 为一个约 80 行的结构拉进整个 Guava 不划算。
 */
public final class DeviceExistenceFilter {

    /**
     * base 位图，{@code long[]} 而非 {@code boolean[]}：后者每个元素占 1 字节，浪费 8 倍空间。
     */
    private final long[] bits;
    private final int bitCount;
    private final int hashCount;
    /**
     * base 重建之后新增的设备；精确集合，因此这部分绝无假阳性也无假阴性。
     */
    private final java.util.Set<DeviceRef> delta;
    private final int baseEntries;
    /**
     * 引导完成前为 true：此时一律回答「可能存在」，绝不能凭空位图把真实设备判死。
     */
    private final boolean permissive;

    private DeviceExistenceFilter(long[] bits, int bitCount, int hashCount,
                                  java.util.Set<DeviceRef> delta, int baseEntries, boolean permissive) {
        this.bits = bits;
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.delta = delta;
        this.baseEntries = baseEntries;
        this.permissive = permissive;
    }

    /**
     * 按目标容量与误判率构建 base。
     *
     * <p>位数 {@code m = -n·ln(p) / (ln2)²}、哈希数 {@code k = (m/n)·ln2} 是 Bloom 的标准取值：
     * 100 万元素、{@code p=0.001} 约需 1.8 MiB 位图与 10 个哈希。
     */
    public static Builder builder(int expectedEntries, double falsePositiveProbability) {
        if (expectedEntries <= 0) {
            throw new IllegalArgumentException("expectedEntries 必须为正数: " + expectedEntries);
        }
        if (falsePositiveProbability <= 0 || falsePositiveProbability >= 1) {
            throw new IllegalArgumentException("误判率必须落在 (0,1): " + falsePositiveProbability);
        }
        double ln2 = Math.log(2);
        long m = (long) Math.ceil(-expectedEntries * Math.log(falsePositiveProbability) / (ln2 * ln2));
        // 位数上限保护：避免配置写错时申请出几个 GiB 的位图
        if (m > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Bloom 位数超出上限: expectedEntries=" + expectedEntries
                                                   + " fpp=" + falsePositiveProbability);
        }
        int bitCount = (int) Math.max(64, m);
        int hashCount = Math.max(1, (int) Math.round((double) bitCount / expectedEntries * ln2));
        return new Builder(new long[(bitCount + 63) >>> 6], bitCount, hashCount);
    }

    /**
     * 空过滤器：只在「设备域尚未引导」时短暂存在，任何查询都返回「可能存在」以免误拒。
     */
    public static DeviceExistenceFilter permissive() {
        return new DeviceExistenceFilter(new long[1], 64, 1, java.util.Set.of(), 0, true);
    }

    /**
     * {@code false} 表示<b>确定</b>不存在；{@code true} 只表示可能存在。
     */
    public boolean mightContain(DeviceRef ref) {
        if (permissive || delta.contains(ref)) {
            return true;
        }
        long hash = ref.stableHash();
        int h1 = (int) hash;
        int h2 = (int) (hash >>> 16) | 1;
        for (int i = 0; i < hashCount; i++) {
            int bit = Math.floorMod(h1 + i * h2, bitCount);
            if ((bits[bit >>> 6] & (1L << (bit & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 追加新增设备，返回新实例。
     *
     * <p>base 位图在实例间<b>结构共享</b>（只传引用不复制）：单设备变更每次都复制 1.8 MiB
     * 位图是不可接受的。只有 delta 这个小集合被复制。
     */
    public DeviceExistenceFilter withAdditions(Collection<DeviceRef> additions) {
        if (additions == null || additions.isEmpty()) {
            return this;
        }
        java.util.Set<DeviceRef> merged = new java.util.HashSet<>(delta);
        merged.addAll(additions);
        return new DeviceExistenceFilter(
            bits, bitCount, hashCount, java.util.Set.copyOf(merged), baseEntries, permissive);
    }

    /**
     * delta 达阈值即触发 base 重建；delta 无限膨胀会让「省一次查询」的收益被内存吃掉。
     */
    public boolean needsRebuild(int deltaMaxEntries) {
        return delta.size() >= deltaMaxEntries;
    }

    public int deltaSize() {
        return delta.size();
    }

    public int baseEntries() {
        return baseEntries;
    }

    /**
     * 位图字节数，用于实例状态上报与容量核算。
     */
    public long estimatedBytes() {
        return (long) bits.length * Long.BYTES;
    }

    /**
     * base 构建器；只在启动引导与低峰重建时使用，禁止在消息线程上跑。
     */
    public static final class Builder {

        private final long[] bits;
        private final int bitCount;
        private final int hashCount;
        private int entries;

        private Builder(long[] bits, int bitCount, int hashCount) {
            this.bits = bits;
            this.bitCount = bitCount;
            this.hashCount = hashCount;
        }

        public void add(DeviceRef ref) {
            long hash = ref.stableHash();
            int h1 = (int) hash;
            int h2 = (int) (hash >>> 16) | 1;
            for (int i = 0; i < hashCount; i++) {
                int bit = Math.floorMod(h1 + i * h2, bitCount);
                bits[bit >>> 6] |= 1L << (bit & 63);
            }
            entries++;
        }

        public DeviceExistenceFilter build() {
            return new DeviceExistenceFilter(
                Arrays.copyOf(bits, bits.length), bitCount, hashCount, java.util.Set.of(), entries, false);
        }
    }
}

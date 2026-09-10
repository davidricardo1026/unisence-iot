package com.unisence.iot.common.metadata;

/**
 * 元数据总线的 Redis 固定键协议（metadata-sync-bus.md §五）。
 *
 * <p><b>刻意不做成 YAML 配置项</b>：admin 与 engine 是两个独立进程、两套配置文件，
 * 键名一旦可配置就会出现「一侧改了另一侧没改」的静默漂移 —— 表现为提示永远收不到，
 * 却要靠 30s MySQL 反熵兜底才发现。写死在共享基座里，两侧编译期即对齐。
 *
 * <p>{@code {metadata}} 是 Redis Cluster 的 hash tag，保证 head / channel / instances
 * 落同一 slot，可用于同一个 Lua 脚本。<b>设备 L2 缓存不使用该 tag</b> ——
 * 百万设备键集中到单 slot 会毁掉 Cluster 的水平扩容，见 §6.6 的 4096 分桶协议。
 */
public final class MetadataRedisKeys {

    /**
     * 最新已提示水位镜像（十进制字符串）。只是 committedHead 的镜像，绝不能反向覆盖 MySQL。
     */
    public static final String HEAD = "unisence:{metadata}:head";

    /**
     * 变更提示频道，载荷为 {@link MetadataChangeHint} 的 JSON。
     */
    public static final String CHANGED_CHANNEL = "unisence:{metadata}:changed";

    /**
     * 存活实例索引 ZSET：member = instanceId，score = 状态 key 的过期时刻（epoch ms）。
     */
    public static final String INSTANCE_INDEX = "unisence:{metadata}:instances";

    private static final String INSTANCE_STATUS_PREFIX = "unisence:{metadata}:instance:";

    /**
     * 设备 L2 分桶 Hash 的键前缀。桶数 {@code 4096} 是<b>键协议而非配置</b>，
     * admin/engine 均不允许各自覆盖，否则同一设备会被路由到不同桶。
     */
    private static final String DEVICE_META_PREFIX = "unisence:device-meta:{d";

    /**
     * 设备 L2 固定桶数。
     */
    public static final int DEVICE_META_BUCKETS = 4096;

    private MetadataRedisKeys() {
    }

    /**
     * 每实例独立状态 key（§9.2）。
     *
     * <p>用「每实例一个 key + TTL」而不是「一个 Hash 每 field 一个 TTL」：Redis 的
     * {@code EXPIRE} 作用于整个 key，Hash field 没有独立 TTL；只有独立 key 才能在实例退出后自然消失。
     */
    public static String instanceStatus(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        return INSTANCE_STATUS_PREFIX + instanceId;
    }

    /**
     * 设备 L2 桶键，形如 {@code unisence:device-meta:{d0042}}。
     *
     * <p>{@code {dNNNN}} 作 hash tag：同桶操作落同一 Cluster slot 以支持 pipeline/Lua，
     * 同时把百万设备分散到 4096 个 slot 组，避免单 slot 热点。
     *
     * @param bucket {@code stableHash(DeviceRef) & (DEVICE_META_BUCKETS - 1)}
     */
    public static String deviceMetaBucket(int bucket) {
        if (bucket < 0 || bucket >= DEVICE_META_BUCKETS) {
            throw new IllegalArgumentException("设备 L2 桶号越界: " + bucket);
        }
        StringBuilder sb = new StringBuilder(DEVICE_META_PREFIX.length() + 5);
        sb.append(DEVICE_META_PREFIX);
        // 固定 4 位十进制，桶键长度恒定，便于运维按前缀统计
        if (bucket < 1000) {
            sb.append('0');
        }
        if (bucket < 100) {
            sb.append('0');
        }
        if (bucket < 10) {
            sb.append('0');
        }
        sb.append(bucket).append('}');
        return sb.toString();
    }
}

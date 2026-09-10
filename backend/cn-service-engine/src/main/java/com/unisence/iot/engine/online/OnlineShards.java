package com.unisence.iot.engine.online;

import com.unisence.iot.metadata.DeviceRef;

/**
 * 在线状态的 256 固定分片（device-online-state-design.md §四）。
 *
 * <p>百万设备压在单个 ZSET / 单个 Redis master 上是不可接受的：一次
 * {@code ZRANGEBYSCORE} 要在百万成员里定位，且所有写入集中到一个 slot，Cluster 白建。
 * 分片后单 shard 复杂度降为 {@code O(log(N/256) + M)}，且负载散到不同 master。
 *
 * <p><b>256 是键协议，不是配置项。</b>改它等于数据重分片 —— 存量设备的租约、状态、owner
 * 会散落在按旧模数计算的 key 里，任何滚动升级都会让同一台设备在新旧实例上被路由到不同 shard。
 * 真要改必须另立迁移方案。
 *
 * <p>哈希算法与元数据 L2 的 4096 bucket <b>共用</b> {@link DeviceRef#stableHash()}
 * （{@code CRC32C(UTF8(productKey) + 0x00 + UTF8(deviceCode))}），保证同一台设备在两套结构里
 * 的路由都由同一个跨进程稳定的函数决定。
 */
public final class OnlineShards {

    /**
     * 固定分片数，键协议的一部分。
     */
    public static final int SHARD_COUNT = 256;

    private static final String LEASE_PREFIX = "iot:online:lease:";
    private static final String STATE_PREFIX = "iot:online:state:";
    private static final String OWNER_PREFIX = "iot:online:owner:";
    private static final String TRANSITION_PREFIX = "iot:online:transition:";
    private static final String SCAN_LOCK_PREFIX = "iot:online:scan:lock:";

    /**
     * 驱动实例租约数量小，不分片。
     */
    public static final String KEY_SVC_LEASE = "iot:online:svc:lease";

    /**
     * transition stream 的固定消费组名。
     */
    public static final String TRANSITION_GROUP = "online-transition-v1";

    private OnlineShards() {
    }

    /**
     * {@code deviceKey} 形如 {@code productKey.deviceCode}；productKey 定长 6。
     */
    public static int of(String deviceKey) {
        int dot = deviceKey.indexOf('.');
        if (dot <= 0 || dot == deviceKey.length() - 1) {
            throw new IllegalArgumentException("非法 deviceKey，无法定位 shard: " + deviceKey);
        }
        return of(deviceKey.substring(0, dot), deviceKey.substring(dot + 1));
    }

    public static int of(String productKey, String deviceCode) {
        return (int) (new DeviceRef(productKey, deviceCode).stableHash() & (SHARD_COUNT - 1));
    }

    /**
     * {@code {oNNN}} 作 hash tag：同一台设备的 lease/state/owner/transition 落<b>同一个 slot</b>，
     * 续租与过期 Lua 才能跨这几个 key 原子执行 —— Redis Cluster 要求单个脚本的所有 key 同 slot。
     */
    public static String tag(int shard) {
        if (shard < 0 || shard >= SHARD_COUNT) {
            throw new IllegalArgumentException("shard 越界: " + shard);
        }
        StringBuilder sb = new StringBuilder(6);
        sb.append("{o");
        if (shard < 100) {
            sb.append('0');
        }
        if (shard < 10) {
            sb.append('0');
        }
        sb.append(shard).append('}');
        return sb.toString();
    }

    public static String lease(int shard) {
        return LEASE_PREFIX + tag(shard);
    }

    public static String state(int shard) {
        return STATE_PREFIX + tag(shard);
    }

    public static String owner(int shard) {
        return OWNER_PREFIX + tag(shard);
    }

    public static String transition(int shard) {
        return TRANSITION_PREFIX + tag(shard);
    }

    public static String scanLock(int shard) {
        return SCAN_LOCK_PREFIX + tag(shard);
    }
}

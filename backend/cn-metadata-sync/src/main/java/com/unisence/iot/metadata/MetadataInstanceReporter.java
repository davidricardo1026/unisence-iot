package com.unisence.iot.metadata;

import com.unisence.iot.common.metadata.MetadataRedisKeys;
import com.unisence.iot.redis.RedisCommands;
import com.unisence.iot.redis.RedisScript;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 实例同步状态上报（metadata-sync-bus.md §九）。
 *
 * <p>纯观测：写失败不影响收敛，也不参与「是否安装快照」的判定。
 *
 * <p>用「每实例独立 TTL key + ZSET 索引」而不是一个大 Hash：Redis 的过期作用于整个 key，
 * Hash field 没有独立 TTL。只有独立 key 才能在实例进程退出后自然消失，
 * 否则 admin 会一直看到早已下线的实例。
 */
@Slf4j
public final class MetadataInstanceReporter {

    /**
     * 一次 Lua 同时写状态与索引。
     *
     * <p>分两次 RTT 写会出现「状态已写但索引未更新」的中间态，admin 恰好在此时查询就会漏掉本实例。
     */
    private static final RedisScript LUA_HEARTBEAT = RedisScript.of("""
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
        redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
        return 1
                                                                        """);

    private final RedisCommands commands;
    private final MetadataSyncService syncService;
    private final MetadataProperties properties;
    private final String instanceId;

    public MetadataInstanceReporter(RedisCommands commands, MetadataSyncService syncService,
                                    MetadataProperties properties) {
        this.commands = commands;
        this.syncService = syncService;
        this.properties = properties;
        this.instanceId = buildInstanceId();
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * {@code {hostname}-{pid}-{bootUlid}}：标识<b>一次进程生命周期</b>。
     *
     * <p>重启必须生成新值 —— 复用旧 ID 会让新进程继承上一次的状态记录，
     * 于是「这个实例刚重启还在引导」和「这个实例卡在旧水位」在 admin 视图里无法区分。
     */
    private static String buildInstanceId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("获取主机名失败，实例 ID 使用 unknown 占位", e);
            hostname = "unknown";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        String bootId = Long.toString(System.currentTimeMillis(), 36)
            + Long.toString(ThreadLocalRandom.current().nextLong(0, 1L << 40), 36);
        return hostname + "-" + pid + "-" + bootId;
    }

    /**
     * 写一次心跳。失败只告警。
     */
    public void report(DeviceMetadataCache deviceCache) {
        try {
            long expiresAt = System.currentTimeMillis() + properties.instanceStatusTtlMs();
            String payload = buildStatusJson(deviceCache);
            LUA_HEARTBEAT.execute(
                commands,
                List.of(MetadataRedisKeys.instanceStatus(instanceId),
                        MetadataRedisKeys.INSTANCE_INDEX),
                List.of(payload,
                        Long.toString(properties.instanceStatusTtlMs()),
                        Long.toString(expiresAt),
                        instanceId));
        } catch (Exception e) {
            log.error("上报元数据实例状态失败: instanceId={}", instanceId, e);
        }
    }

    /**
     * 进程退出时主动摘除索引成员，让 admin 立刻看到实例下线而不必等 TTL。
     */
    public void deregister() {
        try {
            commands.batch(List.of(
                Request.cmd(Command.DEL).arg(MetadataRedisKeys.instanceStatus(instanceId)),
                Request.cmd(Command.ZREM).arg(MetadataRedisKeys.INSTANCE_INDEX).arg(instanceId)));
        } catch (Exception e) {
            log.warn("摘除元数据实例状态失败，等待 TTL 自然过期: instanceId={}", instanceId, e);
        }
    }

    /**
     * 手写 JSON 而不引入序列化库：字段固定、无嵌套，且水位必须编码成<b>字符串</b> ——
     * 经过 JSON number（双精度）会把超过 2^53 的 BIGINT 水位截断。
     */
    private String buildStatusJson(DeviceMetadataCache deviceCache) {
        EngineMetadataSnapshot root = syncService.current();
        DeviceMetadataCache.Stats stats = deviceCache == null ? DeviceMetadataCache.Stats.EMPTY : deviceCache.stats();
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"schemaVersion\":1,");
        sb.append("\"instanceId\":\"").append(instanceId).append("\",");
        sb.append("\"state\":\"").append(syncService.state()).append("\",");
        sb.append("\"appliedHead\":\"").append(syncService.appliedHead()).append("\",");
        sb.append("\"desiredHead\":\"").append(syncService.desiredHead()).append("\",");
        sb.append("\"lastAttemptAt\":").append(syncService.lastAttemptAt()).append(',');
        sb.append("\"lastSuccessAt\":").append(syncService.lastSuccessAt()).append(',');
        sb.append("\"lastBuildDurationMs\":").append(syncService.lastBuildDurationMs()).append(',');
        sb.append("\"deviceCatalogEntries\":").append(root == null ? 0 : root.deviceCatalog().size()).append(',');
        sb.append("\"deviceL1EstimatedWeightBytes\":").append(stats.l1WeightBytes()).append(',');
        sb.append("\"deviceL1HitRate\":").append(format(stats.l1HitRate())).append(',');
        sb.append("\"deviceL2HitRate\":").append(format(stats.l2HitRate())).append(',');
        sb.append("\"deviceDbFallbackRate\":").append(format(stats.dbFallbackRate())).append(',');
        sb.append("\"consecutiveFailures\":").append(syncService.consecutiveFailures()).append(',');
        String errorCode = syncService.lastErrorCode();
        sb.append("\"lastErrorCode\":")
            .append(errorCode == null ? "null" : "\"" + errorCode + "\"");
        sb.append('}');
        return sb.toString();
    }

    /**
     * 命中率保留三位小数：上报的是趋势，不是精确值。
     */
    private static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0.0";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}

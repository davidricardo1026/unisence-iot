package com.unisence.iot.metadata;

/**
 * 元数据同步总线的运行参数（metadata-sync-bus.md §十二）。
 *
 * <p>配置键为 <b>{@code app.<服务>.metadata.*}</b>：{@code cn-service-engine} 绑
 * {@code app.engine.metadata.*}，{@code cn-service-rule-stream} 绑
 * {@code app.rule-stream.metadata.*}。字段与语义完全相同，只是各自挂在自己的服务根下 ——
 * 两个进程消费同一条总线，但可以各自调整探测周期与缓存容量（规则链路的设备热点分布
 * 与存储链路并不一致）。
 *
 * <p>两侧都不是 Spring 应用，因此这里用 record + 紧凑构造器承担启动期校验：
 * 越界配置必须炸在装配阶段，不能拖到第一条消息或第一次收敛才暴露。
 * 校验消息只给键的相对路径（{@code metadata.xxx}），前缀由各自的配置加载器在日志上下文里体现。
 *
 * <p>Redis L2 的 bucket 数 {@code 4096} <b>不在此列</b>：它是键协议而非配置，
 * 任何一侧都不允许覆盖，否则同一台设备会被路由到不同桶。
 */
public record MetadataProperties(
    // ── 触发源 ──
    long redisHeadProbeIntervalMs,
    long dbReconcileIntervalMs,
    double reconcileJitterRatio,
    long coalesceDelayMs,
    long maxCoalesceDelayMs,
    int maxIncrementalScopes,
    long retryInitialDelayMs,
    long retryMaxDelayMs,
    int failureAlertThreshold,
    // ── 实例状态 ──
    long instanceHeartbeatIntervalMs,
    long instanceStatusTtlMs,
    // ── 小域保护上限 ──
    int productMaxEntries,
    int thingModelDefinitionMaxEntries,
    int ruleMaxEntries,
    long bootstrapSmallDomainMaxBytes,
    // ── 设备版本目录 ──
    int deviceCatalogShardCount,
    int deviceCatalogLoadPageSize,
    int deviceCatalogMaxEntries,
    double deviceExistenceFpp,
    int deviceExistenceDeltaMaxEntries,
    int deviceExistenceRebuildIntervalHours,
    // ── 三级设备缓存 ──
    long deviceL1MaxWeightBytes,
    int deviceL1ExpireAfterAccessSeconds,
    int deviceCacheEntryMaxBytes,
    int deviceL2ReadBatchSize,
    int deviceDbFallbackBatchSize,
    int deviceDbFallbackMaxConcurrency,
    int deviceDbFallbackQueueHighWatermark,
    int deviceDbFallbackQueueLowWatermark,
    long deviceLoadTimeoutMs,
    // ── 未知设备修复 ──
    int unknownDeviceCacheMaxEntries,
    long unknownDeviceCacheTtlMs,
    int unknownDeviceRepairStripes,
    int unknownDeviceRepairMaxConcurrency,
    long unknownDeviceHeadProbeFreshnessMs,
    long unknownDeviceReconcileTimeoutMs) {

    private static final int MIN_CATALOG_SHARDS = 256;
    private static final int MAX_CATALOG_SHARDS = 4096;
    private static final int MIN_REPAIR_STRIPES = 16;
    private static final int MAX_REPAIR_STRIPES = 1024;
    private static final double MAX_EXISTENCE_FPP = 0.01;
    /**
     * TTL 必须大于心跳周期 3 倍，否则一次 GC 停顿就会让实例在 admin 视图里假死。
     */
    private static final int TTL_HEARTBEAT_RATIO = 3;

    public MetadataProperties {
        requirePositive(redisHeadProbeIntervalMs, "metadata.redis-head-probe-interval-ms");
        // 反熵是 Redis 整体不可用时唯一仍然正确的发现路径，因此只允许调周期，不允许关
        requirePositive(dbReconcileIntervalMs, "metadata.db-reconcile-interval-ms");
        requirePositive(coalesceDelayMs, "metadata.coalesce-delay-ms");
        requirePositive(maxCoalesceDelayMs, "metadata.max-coalesce-delay-ms");
        requirePositive(maxIncrementalScopes, "metadata.max-incremental-scopes");
        requirePositive(retryInitialDelayMs, "metadata.retry-initial-delay-ms");
        requirePositive(retryMaxDelayMs, "metadata.retry-max-delay-ms");
        requirePositive(failureAlertThreshold, "metadata.failure-alert-threshold");
        requirePositive(instanceHeartbeatIntervalMs, "metadata.instance-heartbeat-interval-ms");
        requirePositive(instanceStatusTtlMs, "metadata.instance-status-ttl-ms");
        requirePositive(productMaxEntries, "metadata.product-max-entries");
        requirePositive(thingModelDefinitionMaxEntries, "metadata.thing-model-definition-max-entries");
        requirePositive(ruleMaxEntries, "metadata.rule-max-entries");
        requirePositive(bootstrapSmallDomainMaxBytes, "metadata.bootstrap-small-domain-max-bytes");
        requirePositive(deviceCatalogLoadPageSize, "metadata.device-catalog-load-page-size");
        requirePositive(deviceCatalogMaxEntries, "metadata.device-catalog-max-entries");
        requirePositive(deviceExistenceDeltaMaxEntries, "metadata.device-existence-delta-max-entries");
        requirePositive(deviceExistenceRebuildIntervalHours,
                        "metadata.device-existence-rebuild-interval-hours");
        requirePositive(deviceL1MaxWeightBytes, "metadata.device-l1-max-weight-bytes");
        requirePositive(deviceL1ExpireAfterAccessSeconds, "metadata.device-l1-expire-after-access-seconds");
        requirePositive(deviceCacheEntryMaxBytes, "metadata.device-cache-entry-max-bytes");
        requirePositive(deviceL2ReadBatchSize, "metadata.device-l2-read-batch-size");
        requirePositive(deviceDbFallbackBatchSize, "metadata.device-db-fallback-batch-size");
        requirePositive(deviceDbFallbackMaxConcurrency, "metadata.device-db-fallback-max-concurrency");
        requirePositive(deviceDbFallbackQueueHighWatermark,
                        "metadata.device-db-fallback-queue-high-watermark");
        requirePositive(deviceDbFallbackQueueLowWatermark,
                        "metadata.device-db-fallback-queue-low-watermark");
        requirePositive(deviceLoadTimeoutMs, "metadata.device-load-timeout-ms");
        requirePositive(unknownDeviceCacheMaxEntries, "metadata.unknown-device-cache-max-entries");
        requirePositive(unknownDeviceCacheTtlMs, "metadata.unknown-device-cache-ttl-ms");
        requirePositive(unknownDeviceRepairMaxConcurrency,
                        "metadata.unknown-device-repair-max-concurrency");
        requirePositive(unknownDeviceHeadProbeFreshnessMs,
                        "metadata.unknown-device-head-probe-freshness-ms");
        requirePositive(unknownDeviceReconcileTimeoutMs, "metadata.unknown-device-reconcile-timeout-ms");

        if (reconcileJitterRatio < 0 || reconcileJitterRatio >= 1) {
            throw new IllegalArgumentException(
                "metadata.reconcile-jitter-ratio 必须落在 [0,1): " + reconcileJitterRatio);
        }
        if (coalesceDelayMs > maxCoalesceDelayMs) {
            throw new IllegalArgumentException(
                "metadata.coalesce-delay-ms(" + coalesceDelayMs + ") 不得大于 max-coalesce-delay-ms("
                    + maxCoalesceDelayMs + ")：前者是首个提示的等待，后者是持续变化时的强制开始上限");
        }
        if (retryInitialDelayMs > retryMaxDelayMs) {
            throw new IllegalArgumentException("metadata.retry-initial-delay-ms 不得大于 retry-max-delay-ms");
        }
        if (instanceStatusTtlMs < instanceHeartbeatIntervalMs * TTL_HEARTBEAT_RATIO) {
            throw new IllegalArgumentException(
                "metadata.instance-status-ttl-ms(" + instanceStatusTtlMs + ") 必须大于心跳周期的 "
                    + TTL_HEARTBEAT_RATIO + " 倍：否则一次 GC 停顿就会让实例在 admin 视图里假死");
        }
        requirePowerOfTwoInRange(deviceCatalogShardCount, MIN_CATALOG_SHARDS, MAX_CATALOG_SHARDS,
                                 "metadata.device-catalog-shard-count");
        requirePowerOfTwoInRange(unknownDeviceRepairStripes, MIN_REPAIR_STRIPES, MAX_REPAIR_STRIPES,
                                 "metadata.unknown-device-repair-stripes");
        if (deviceExistenceFpp <= 0 || deviceExistenceFpp > MAX_EXISTENCE_FPP) {
            throw new IllegalArgumentException(
                "metadata.device-existence-fpp 必须落在 (0," + MAX_EXISTENCE_FPP + "]: "
                    + deviceExistenceFpp);
        }
        if (deviceDbFallbackQueueLowWatermark >= deviceDbFallbackQueueHighWatermark) {
            throw new IllegalArgumentException(
                "metadata.device-db-fallback-queue-low-watermark 必须小于 high-watermark："
                    + "否则暂停分区后永远达不到恢复条件");
        }
        if (deviceCacheEntryMaxBytes > deviceL1MaxWeightBytes) {
            throw new IllegalArgumentException(
                "metadata.device-cache-entry-max-bytes 不得超过 device-l1-max-weight-bytes："
                    + "单条就能撑满权重预算会让 L1 退化成只存一条");
        }
    }

    /**
     * 反熵与探测都按实例加独立随机抖动，避免整个集群在同一毫秒去查同一行 head。
     */
    public long jitter(long baseMs) {
        if (reconcileJitterRatio == 0) {
            return baseMs;
        }
        double delta = baseMs * reconcileJitterRatio;
        return (long) (baseMs + (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 2 - 1) * delta);
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数，当前值: " + value);
        }
    }

    private static void requirePowerOfTwoInRange(int value, int min, int max, String key) {
        if (value < min || value > max || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(
                key + " 必须是 [" + min + "," + max + "] 区间内的 2 次幂，当前值: " + value);
        }
    }
}

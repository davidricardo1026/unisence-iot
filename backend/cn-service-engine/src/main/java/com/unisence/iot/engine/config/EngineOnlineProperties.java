package com.unisence.iot.engine.config;

/**
 * {@code app.engine.online.*}（device-online-state-design.md §九）。
 *
 * @param defaultTtlSeconds     产品未配置 {@code online_ttl_seconds} 时的兜底值
 * @param serviceTtlSeconds     驱动实例租约时长；须 ≥ 驱动心跳周期的 2~3 倍
 * @param scanIntervalMs        判活扫描周期，决定离线判定的最大延迟（真实延迟 ≤ ttl + scanInterval）
 * @param scanBatchSize         单轮扫描取出的最大过期条目数；取满会立即续跑下一轮
 * @param scanLockTtlMs         每个 shard 扫描锁的租期，必须大于单批最坏耗时；长扫描按 token 原子续期
 * @param renewThrottleMs       同一设备在该窗口内只真正续租一次，其余消息零 Redis 交互
 * @param renewFlushBatchSize   续租攒批（Redis pipeline）的批大小
 * @param renewFlushDelayMs     续租攒批的最长等待
 * @param renewPendingMax       续租攒批器的<b>队列容量</b>，队列满时丢最旧一条。
 *                              <b>与 {@code maxPendingTransitionsAlert} 是两个概念，禁止复用</b> ——
 *                              后者是 transition PEL 的<b>告警阈值</b>（只告警/背压、禁止 trim），
 *                              两者的定值依据互相矛盾：告警阈值要略高于正常峰值，
 *                              队列容量要等于「排空速率 × 可容忍突发时长」
 *                              （排空速率 = renewFlushBatchSize / renewFlushDelayMs）。
 *                              见 device-online-state-design.md「续租攒批器的容量必须独立配置」
 * @param flushBatchSize        跳变落库攒批的批大小
 * @param flushDelayMs          跳变落库攒批的最长等待；须 ≤1000，否则 last_online_at 误差超出 datetime 秒精度
 * @param maxPendingTransitions 待写跳变积压告警阈值
 */
public record EngineOnlineProperties(
    int defaultTtlSeconds,
    int serviceTtlSeconds,
    long scanIntervalMs,
    int scanBatchSize,
    int scanShardConcurrency,
    long scanLockTtlMs,
    long renewThrottleMs,
    int renewThrottleMaxEntries,
    int renewFlushBatchSize,
    long renewFlushDelayMs,
    int renewPendingMax,
    int flushBatchSize,
    long flushDelayMs,
    int transitionReadBatchSize,
    long transitionBlockMs,
    long transitionClaimIdleMs,
    int transitionDrainShardConcurrency,
    int maxPendingTransitionsAlert) {

    /**
     * {@code last_online_at} 落 datetime（秒精度），攒批窗口超过 1 秒就会把误差放大到精度之外。
     */
    private static final long MAX_FLUSH_DELAY_MS = 1000;

    public EngineOnlineProperties {
        requirePositive(defaultTtlSeconds, "app.engine.online.default-ttl-seconds");
        requirePositive(serviceTtlSeconds, "app.engine.online.service-ttl-seconds");
        requirePositive(scanIntervalMs, "app.engine.online.scan-interval-ms");
        requirePositive(scanBatchSize, "app.engine.online.scan-batch-size");
        requirePositive(scanLockTtlMs, "app.engine.online.scan-lock-ttl-ms");
        requirePositive(renewThrottleMs, "app.engine.online.renew-throttle-ms");
        requirePositive(renewFlushBatchSize, "app.engine.online.renew-flush-batch-size");
        requirePositive(renewFlushDelayMs, "app.engine.online.renew-flush-delay-ms");
        requirePositive(renewPendingMax, "app.engine.online.renew-pending-max");
        if (renewPendingMax < renewFlushBatchSize) {
            // BatchAccumulator 自己也会校验，但在此提前给出带配置键名的报错
            throw new IllegalArgumentException(
                "app.engine.online.renew-pending-max(" + renewPendingMax + ") 不得小于 renew-flush-batch-size("
                    + renewFlushBatchSize + ")：否则一批还没攒够就开始丢续租");
        }
        requirePositive(flushBatchSize, "app.engine.online.flush-batch-size");
        requirePositive(flushDelayMs, "app.engine.online.flush-delay-ms");
        requirePositive(scanShardConcurrency, "app.engine.online.scan-shard-concurrency");
        requirePositive(renewThrottleMaxEntries, "app.engine.online.renew-throttle-max-entries");
        requirePositive(transitionReadBatchSize, "app.engine.online.transition-read-batch-size");
        requirePositive(transitionBlockMs, "app.engine.online.transition-block-ms");
        requirePositive(transitionClaimIdleMs, "app.engine.online.transition-claim-idle-ms");
        requirePositive(transitionDrainShardConcurrency,
                        "app.engine.online.transition-drain-shard-concurrency");
        requirePositive(maxPendingTransitionsAlert, "app.engine.online.max-pending-transitions-alert");

        requireRange(scanShardConcurrency, 1, 32, "app.engine.online.scan-shard-concurrency");
        requireRange(transitionDrainShardConcurrency, 1, 32,
                     "app.engine.online.transition-drain-shard-concurrency");
        if (transitionClaimIdleMs <= transitionBlockMs) {
            // 接管阈值不大于阻塞读时长时，正在正常处理中的项会被别的实例抢走，
            // 造成同一条跳变被反复接管而没人真正完成
            throw new IllegalArgumentException(
                "app.engine.online.transition-claim-idle-ms(" + transitionClaimIdleMs
                    + ") 必须大于 transition-block-ms(" + transitionBlockMs + ")");
        }

        if (flushDelayMs > MAX_FLUSH_DELAY_MS) {
            throw new IllegalArgumentException(
                "app.engine.online.flush-delay-ms(" + flushDelayMs + ") 不得超过 " + MAX_FLUSH_DELAY_MS
                    + "：last_online_at 取批次时刻，窗口超过 datetime 的秒精度会让跳变时间失真");
        }
        // §5.3 硬约束：节流窗口接近 TTL 会造成持续误判离线（expireAt 基于窗口内第一条消息）
        long maxThrottle = defaultTtlSeconds * 1000L / 3;
        if (renewThrottleMs > maxThrottle) {
            throw new IllegalArgumentException(
                "app.engine.online.renew-throttle-ms(" + renewThrottleMs + ") 不得超过 default-ttl-seconds 的 1/3("
                    + maxThrottle + "ms)：节流会让离线判定提前，窗口过大将造成持续误判");
        }
    }

    private static void requireRange(int value, int min, int max, String key) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " 必须落在 [" + min + "," + max + "]，当前值: " + value);
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数，当前值: " + value);
        }
    }
}

package com.unisence.iot.engine.config;

/**
 * {@code app.engine.ingress.*} 的绑定结果（application-configuration.md §3.1）。
 *
 * <p>engine 非 Spring，因此这里是纯 Java record 而非 {@code @ConfigurationProperties} 类，
 * 由 {@link EngineConfigLoader} 读同名键构造 —— 键空间与 Spring 服务完全一致，
 * 换成 Spring 绑定时不需要改 YAML。
 *
 * <p>只登记<b>当前代码真正读取</b>的字段。规则执行预算、心跳参数等仍属 {@code app.engine.ingress.*}
 * 的规划范围，但要等规则 topology 落地时随代码一起加，不在此处预留空字段。
 *
 * @param bootstrapServers        Kafka 接入点
 * @param groupId                 存储链路消费组；与规则 topology 的 application.id 相互独立
 * @param groupInstanceId         静态成员 ID，可为 {@code null}；配置后单机重启不触发全组 rebalance
 * @param rawDataTopic            属性遥测 Topic
 * @param eventTopic              事件 Topic（设备创建/事件/设备心跳/服务心跳）
 * @param eventGroupId            事件链路消费组；与属性链路分开，避免遥测洪峰把事件顶在队尾
 * @param dlqTopic                死信 Topic
 * @param consumerCount           每条链路的并行消费者数（各自独立 poll 循环，同消费组）
 * @param maxPollRecords          单次 poll 最大记录数
 * @param pollTimeoutMs           poll 阻塞超时
 * @param fetchMinBytes           broker 攒够多少字节才返回
 * @param fetchMaxWaitMs          攒批的最长等待
 * @param maxPropertyMessageBytes 属性消息解码前的字节上限，超限直接进 DLQ
 * @param maxEventMessageBytes    事件消息解码前的字节上限；事件可带较大 params，故与属性分开配置
 * @param autoOffsetReset         无有效 offset 时的重置策略；存储链路默认 earliest，
 *                                latest 会静默跳过积压且无告警（engine-hotpath-optimization.md §七）
 * @param deviceCreateBatchSize   单个建档事务的设备数上限；过大则持锁过久，
 *                                过小则收敛轮数上升（engine-hotpath-optimization.md §10.8）
 * @param retryBackoffMs          可重试故障的退避基数，按连续失败次数线性增长
 * @param maxRetryBackoffMs       退避上限；下游长时间不可用时分区停住并持续告警，不会放弃重试
 */
public record EngineIngressProperties(
    String bootstrapServers,
    String groupId,
    String groupInstanceId,
    String rawDataTopic,
    String eventTopic,
    String eventGroupId,
    String dlqTopic,
    int consumerCount,
    int maxPollRecords,
    long pollTimeoutMs,
    int fetchMinBytes,
    int fetchMaxWaitMs,
    int maxPropertyMessageBytes,
    int maxEventMessageBytes,
    String autoOffsetReset,
    int deviceCreateBatchSize,
    long retryBackoffMs,
    long maxRetryBackoffMs) {

    public EngineIngressProperties {
        requirePositive(consumerCount, "ingress.consumer-count");
        requirePositive(maxPollRecords, "app.engine.ingress.poll.max-records");
        requirePositive(pollTimeoutMs, "app.engine.ingress.poll.timeout-ms");
        requirePositive(maxPropertyMessageBytes, "app.engine.ingress.max-property-message-bytes");
        requirePositive(maxEventMessageBytes, "app.engine.ingress.max-event-message-bytes");
        if (!"earliest".equals(autoOffsetReset) && !"latest".equals(autoOffsetReset)) {
            throw new IllegalArgumentException(
                "app.engine.ingress.auto-offset-reset 只能是 earliest 或 latest，当前值: " + autoOffsetReset);
        }
        requirePositive(deviceCreateBatchSize, "app.engine.ingress.device-create-batch-size");
        requirePositive(retryBackoffMs, "app.engine.ingress.retry-backoff-ms");
        requirePositive(maxRetryBackoffMs, "app.engine.ingress.max-retry-backoff-ms");
        if (maxRetryBackoffMs < retryBackoffMs) {
            throw new IllegalArgumentException(
                "app.engine.ingress.max-retry-backoff-ms 不得小于 retry-backoff-ms");
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数，当前值: " + value);
        }
    }
}

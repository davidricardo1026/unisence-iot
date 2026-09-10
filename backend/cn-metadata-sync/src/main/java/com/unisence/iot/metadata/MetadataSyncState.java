package com.unisence.iot.metadata;

/**
 * 实例同步状态（metadata-sync-bus.md §9.2）。
 *
 * <p>纯观测用途，<b>不参与「是否安装快照」的判定</b>：状态写失败、Redis 不可用都不影响收敛正确性。
 */
public enum MetadataSyncState {

    /**
     * 尚未安装首个完整根快照；此时不得部署任何 Kafka 消费 verticle。
     */
    BOOTSTRAPPING,

    /**
     * {@code appliedHead >= desiredHead} 且最近一次反熵成功。
     */
    READY,

    /**
     * 已发现更高水位或正在构建候选根。
     */
    CONVERGING,

    /**
     * 有 LKG 可继续消费，但连续收敛失败已达告警阈值。
     */
    DEGRADED
}

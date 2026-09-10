package com.unisence.iot.rule.sdk;

/**
 * Kafka record 的只读摘要。脚本可读，用于诊断与端到端延迟计算，不可用于任何写操作。
 *
 * @param timestamp Kafka record timestamp，epoch millis
 */
public record KafkaMeta(int partition, long offset, long timestamp) {

    /**
     * admin 的 compile/test 场景没有真实 Kafka record。
     */
    public static final KafkaMeta NONE = new KafkaMeta(-1, -1L, 0L);
}

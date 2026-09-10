package com.unisence.iot.metadata;

import com.unisence.iot.rule.config.OutputFormat;

/**
 * 快照中固化的一个 Kafka 输出目标：来自 {@code us_iot_rule_kafka_output} 的精确 Topic 与编码。
 *
 * <p>输出定义被引用后不可改路，因此不需要独立元数据域；随规则聚合一起进入 {@link RuleSnapshot}。
 *
 * @param outputId    输出定义 ID
 * @param targetTopic 静态精确 Topic
 * @param format      value 编码
 */
public record KafkaOutputTarget(long outputId, String targetTopic, OutputFormat format) {

    public KafkaOutputTarget {
        if (targetTopic == null || targetTopic.isBlank()) {
            throw new IllegalArgumentException("targetTopic 不能为空: outputId=" + outputId);
        }
        if (format == null) {
            throw new IllegalArgumentException("format 不能为空: outputId=" + outputId);
        }
    }
}

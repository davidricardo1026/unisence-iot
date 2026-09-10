package com.unisence.iot.rule.config;

/**
 * Kafka 输出定义的用途。一个输出定义只服务一种用途，避免告警 map 与原始上行信封混进同一 Topic。
 *
 * <ul>
 *   <li>{@link #RULE_OUTPUT} —— 即时/窗口规则档位绑定，value 为输出脚本返回的 map；</li>
 *   <li>{@link #ROUTE} —— 透传路由规则绑定，value 为上行信封原样字节或其 canonical JSON。</li>
 * </ul>
 */
public enum KafkaOutputPurpose {
    RULE_OUTPUT,
    ROUTE
}

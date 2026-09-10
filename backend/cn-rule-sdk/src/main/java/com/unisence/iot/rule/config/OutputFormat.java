package com.unisence.iot.rule.config;

/**
 * Kafka 输出 value 的编码格式。
 *
 * <p>属于输出定义（Topic 登记项）的业务元数据：一个 Topic 只有一种编码，
 * 下游对该 Topic 的反序列化方式因此稳定。规则输出与透传路由共用本枚举，
 * 但语义随用途不同 —— 规则输出指脚本返回 map 的编码，透传指上行信封的编码。
 */
public enum OutputFormat {
    JSON,
    MESSAGEPACK
}

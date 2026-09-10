package com.unisence.iot.engine.config;

import java.util.List;
import java.util.Set;

/**
 * {@code app.engine.route.*} —— 透传路由的绑定结果，由 {@link EngineConfigLoader} 读同名键构造。
 *
 * <p>数字均为待压测初值，禁止就地调优。
 *
 * @param allowedTopicPrefixes 可写 Topic 前缀白名单
 * @param forbiddenTopics      禁止作为透传目标的 Topic；启动期必须包含 ingress 的 raw-data / event / dlq Topic
 * @param lingerMs             producer {@code linger.ms}；每批结束会显式 {@code flush()}，该值只影响批内合并
 * @param compressionType      producer {@code compression.type}
 * @param requestTimeoutMs     producer {@code request.timeout.ms}
 * @param deliveryTimeoutMs    producer {@code delivery.timeout.ms}；决定目标 Topic 不可写时存储链路最长停顿，须独立定值
 */
public record EngineRouteProperties(
    List<String> allowedTopicPrefixes,
    Set<String> forbiddenTopics,
    int lingerMs,
    String compressionType,
    int requestTimeoutMs,
    int deliveryTimeoutMs) {

    public EngineRouteProperties {
        if (allowedTopicPrefixes == null || allowedTopicPrefixes.isEmpty()) {
            throw new IllegalArgumentException("app.engine.route.allowed-topic-prefixes 不能为空");
        }
        if (compressionType == null || compressionType.isBlank()) {
            throw new IllegalArgumentException("app.engine.route.compression-type 不能为空");
        }
        allowedTopicPrefixes = List.copyOf(allowedTopicPrefixes);
        forbiddenTopics = forbiddenTopics == null ? Set.of() : Set.copyOf(forbiddenTopics);
    }

    /**
     * 启动期自检：禁止集合必须覆盖本进程消费与写入的全部内部 Topic。
     *
     * @throws IllegalArgumentException 任一内部 Topic 不在 {@link #forbiddenTopics()} 中
     */
    public void requireForbidden(String rawDataTopic, String eventTopic, String dlqTopic) {
        requireContains(rawDataTopic);
        requireContains(eventTopic);
        requireContains(dlqTopic);
    }

    private void requireContains(String topic) {
        if (!forbiddenTopics.contains(topic)) {
            throw new IllegalArgumentException(
                "app.engine.route.forbidden-topics 必须包含内部 Topic: " + topic);
        }
    }
}

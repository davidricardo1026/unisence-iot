package com.unisence.iot.engine.route;

import org.apache.kafka.common.metrics.MeasurableStat;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.apache.kafka.common.metrics.stats.CumulativeSum;
import org.apache.kafka.common.metrics.stats.Max;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 透传路由指标，注册到 engine 既有的 Kafka {@link Metrics} 注册表（JMX 域 {@code unisence.engine}）。
 *
 * <p>成功指标表示 Kafka ack 成功，不宣称下游业务动作已完成。按 topic 打标签的 sensor 在首次出现时创建并缓存，
 * 热路径只做计数器加法。
 *
 * <ul>
 *   <li>{@code iot_route_forward_total{topic}} —— 成功 ack 的透传记录数</li>
 *   <li>{@code iot_route_forward_bytes_total{topic}} —— 成功 ack 的 value 字节数</li>
 *   <li>{@code iot_route_forward_failure_total{topic}} —— 触发整批回退的发送失败次数</li>
 *   <li>{@code iot_route_flush_ms_sum / _count / _max} —— 每批 flush + 全部 get 的耗时</li>
 *   <li>{@code iot_route_json_encode_total} —— JSON 编码次数（核对「每条最多编一次」）</li>
 * </ul>
 */
public final class RouteMetrics {

    private static final String GROUP = "unisence-engine";

    private final Metrics metrics;
    private final Map<String, Sensor> sensors = new ConcurrentHashMap<>();
    private final Map<String, TopicSensors> byTopic = new ConcurrentHashMap<>();
    private final Sensor flushSensor;
    private final Sensor jsonEncodeSensor;

    /**
     * @param registry engine 共享的指标注册表（由 {@code EngineMetrics} 暴露）
     */
    public RouteMetrics(Metrics registry) {
        this.metrics = registry;
        this.flushSensor = sensor("iot_route_flush_ms", Map.of(), () -> {
            Sensor s = metrics.sensor("iot_route_flush_ms");
            s.add(metrics.metricName("iot_route_flush_ms_sum", GROUP, "每批 flush + 全部 get 的耗时（累计）", Map.of()),
                  new CumulativeSum());
            s.add(metrics.metricName("iot_route_flush_ms_count", GROUP, "每批 flush + 全部 get 的次数", Map.of()),
                  new CumulativeCount());
            s.add(metrics.metricName("iot_route_flush_ms_max", GROUP, "每批 flush + 全部 get 的耗时（最大值）", Map.of()),
                  new Max());
            return s;
        });
        this.jsonEncodeSensor = addStat("iot_route_json_encode_total", "JSON 编码次数", Map.of(),
                                        new CumulativeCount());
    }

    public void forwarded(String topic, int valueBytes) {
        TopicSensors topicSensors = topicSensors(topic);
        topicSensors.total.record(1);
        topicSensors.bytes.record(valueBytes);
    }

    public void forwardFailed(String topic) {
        topicSensors(topic).failure.record(1);
    }

    public void flushCompleted(long elapsedMillis) {
        flushSensor.record(elapsedMillis);
    }

    public void jsonEncoded() {
        jsonEncodeSensor.record(1);
    }

    private TopicSensors topicSensors(String topic) {
        return byTopic.computeIfAbsent(topic, this::createTopicSensors);
    }

    private TopicSensors createTopicSensors(String topic) {
        Map<String, String> tags = tags("topic", topic);
        return new TopicSensors(
            addStat("iot_route_forward_total", "成功 ack 的透传记录数", tags, new CumulativeCount()),
            addStat("iot_route_forward_bytes_total", "成功 ack 的 value 字节数", tags, new CumulativeSum()),
            addStat("iot_route_forward_failure_total", "触发整批回退的发送失败次数", tags, new CumulativeCount()));
    }

    private Sensor addStat(String name, String description, Map<String, String> tags, MeasurableStat stat) {
        return sensor(name, tags, () -> {
            Sensor s = metrics.sensor(sensorName(name, tags));
            s.add(metrics.metricName(name, GROUP, description, tags), stat);
            return s;
        });
    }

    private Sensor sensor(String name, Map<String, String> tags, java.util.function.Supplier<Sensor> factory) {
        return sensors.computeIfAbsent(sensorName(name, tags), key -> factory.get());
    }

    private static String sensorName(String name, Map<String, String> tags) {
        if (tags.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name);
        tags.forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        return sb.toString();
    }

    private static Map<String, String> tags(String k1, String v1) {
        Map<String, String> map = new LinkedHashMap<>(2);
        map.put(k1, v1);
        return map;
    }

    private record TopicSensors(Sensor total, Sensor bytes, Sensor failure) {
    }
}

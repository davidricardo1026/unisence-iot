package com.unisence.iot.engine.metrics;

import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.*;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.CumulativeCount;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.utils.Time;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * engine 运行指标（observability.md「engine 指标」）。
 *
 * <p><b>为什么是 Kafka 的 {@code Metrics} 而不是 micrometer</b>：本模块禁止 HTTP 端点，
 * 自建 {@code /metrics} 不可行；而 {@code kafka-clients} 已在 classpath 上，
 * 其 {@link JmxReporter} 能把指标直接暴露到 JMX —— 与 {@code cn-service-rule-stream}
 * 的 {@code RuleMetrics} 走同一条采集通道，采集端不必为两个服务配两套。引入 micrometer
 * 只会多一个依赖，而它在「无 HTTP、只需 JMX」的场景下没有额外价值。
 *
 * <p><b>标签纪律（硬约束）</b>：所有标签值必须是代码内的枚举常量。
 * 禁止把异常 message、脚本内容或任何随数据变化的字符串放进标签，
 * <b>{@code deviceId} / {@code productKey} / {@code msgId} 一律禁止</b> ——
 * 百万设备规模会直接变成监控系统的时序基数。
 *
 * <p>本类线程安全：{@link Sensor#record} 本身线程安全，sensor 实例按名缓存于
 * {@link ConcurrentHashMap}，可从摄入 poll 线程、时序写入池、在线扫描池并发调用。
 */
public final class EngineMetrics implements AutoCloseable, com.unisence.iot.redis.RedisObserver,
    com.unisence.iot.metadata.MetadataObserver {

    private static final String GROUP = "unisence-engine";

    /**
     * {@link #ingressDiscarded} 的 reason 值域，禁止在调用点现编字符串。
     */
    public static final String REASON_DEVICE_UNKNOWN = "device_unknown";
    public static final String REASON_THING_MODEL = "thing_model_violation";

    /**
     * {@link #timeSeriesFlush} 的 result 值域。
     */
    public static final String RESULT_OK = "ok";
    public static final String RESULT_RETRYABLE = "retryable";
    public static final String RESULT_REJECTED = "rejected";

    private final Metrics metrics;
    private final Map<String, Sensor> sensors = new ConcurrentHashMap<>();

    /**
     * 续租节流的两个 sensor，<b>在构造期解析完毕</b>。
     *
     * <p><b>为什么这两个必须预解析，而别的调用点不用</b>（hotpath-findings.md H19）：
     * {@link #onlineRenewThrottle} 由 {@code DeviceOnlineStateService.renew} 调用，
     * 而后者的契约是「<b>任意上行设备消息调用</b>」—— 它是本类唯一的**每消息级**调用点，
     * 其余（{@code ingressAccepted} / {@code timeSeriesFlush} …）都是每批或每次 IO 一调，
     * 频率低三个数量级。
     *
     * <p>走通用 {@code counter(name, desc, tags)} 路径的每次调用都要
     * ① 新建 {@code LinkedHashMap} 装标签 ② 用 {@code StringBuilder} 拼出 35 字符的 sensor 名
     * ③ 拿这个**每次都新建**的 String 去 {@code computeIfAbsent} —— 新 String 的 hashCode
     * 缓存为 0，每次都要重算全长哈希。实测该路径占 4.70% 分配、3.66% CPU，
     * 且 {@code ConcurrentHashMap.get} 的归因里 {@code renew} 是全场第一（46 样本）。
     *
     * <p><b>判据</b>：标签取值集合有界且调用频率达到每消息级 → 必须预解析成字段。
     * 这里取值只有 {@code throttled} / {@code passed} 两个，构造期即可全部建好。
     */
    private final Sensor renewThrottledSensor;
    private final Sensor renewPassedSensor;

    public EngineMetrics() {
        JmxReporter reporter = new JmxReporter();
        List<MetricsReporter> reporters = new ArrayList<>();
        reporters.add(reporter);
        // MetricsContext 决定 JMX 域名前缀；与 rule-stream 的 group 命名保持同族
        this.metrics = new Metrics(new MetricConfig(), reporters, Time.SYSTEM,
                                   new KafkaMetricsContext("unisence.engine"));
        // 与首次调用时惰性创建完全等价，只是把它提前到构造期，热路径上不再重复解析
        this.renewThrottledSensor = counter("iot_online_renew_total", "续租调用数",
                                            tags("result", "throttled"));
        this.renewPassedSensor = counter("iot_online_renew_total", "续租调用数",
                                         tags("result", "passed"));
    }

    // ────────────────────────── 摄入 ──────────────────────────

    public void ingressReceived(String topic, int count) {
        counter("iot_ingress_received_total", "摄入收到的记录数", tags("topic", topic)).record(count);
    }

    public void ingressAccepted(String topic, int count) {
        counter("iot_ingress_accepted_total", "通过准入与校验、进入落库的记录数",
                tags("topic", topic)).record(count);
    }

    /**
     * 确认丢弃（不落库、不进 DLQ）。{@code reason} 只允许本类的 {@code REASON_*} 常量。
     *
     * <p>这是本组指标里最不可替代的一个：Kafka 客户端自带的 lag 指标只能告诉你「堆积了」，
     * 告诉不了你「是下游慢、还是大批设备未注册被丢、还是毒消息在刷 DLQ」，
     * 而这三者的处置完全不同。
     */
    public void ingressDiscarded(String topic, String reason, int count) {
        counter("iot_ingress_discarded_total", "确认未注册等原因被丢弃的记录数",
                tags("topic", topic, "reason", reason)).record(count);
    }

    public void ingressDlq(String topic, String reason, int count) {
        counter("iot_ingress_dlq_total", "投递到 DLQ 的记录数",
                tags("topic", topic, "reason", reason)).record(count);
    }

    /**
     * 单批处理耗时。R10.1 对 {@code max-poll-records} 的定值依据就是它与
     * {@code max.poll.interval.ms} 的余量（2026-08-09 实测 12.6ms，余量约 2.4 万倍）。
     */
    public void ingressBatchMillis(String topic, long millis) {
        gauge("iot_ingress_batch_ms", "单个 poll 批的处理耗时", tags("topic", topic)).record(millis);
    }

    // ────────────────────────── 时序数据库 ──────────────────────────

    public void timeSeriesFlush(String table, long millis, int rows, String result) {
        gauge("iot_timeseries_flush_ms", "单次时序数据批写耗时", tags("table", table)).record(millis);
        gauge("iot_timeseries_flush_rows", "单次时序数据批写行数", tags("table", table)).record(rows);
        counter("iot_timeseries_write_total", "时序数据库写入结果计数", tags("result", result)).record(1);
    }

    public void timeSeriesWriterWaitMillis(long millis) {
        gauge("iot_timeseries_writer_wait_ms", "等待时序属性写入线程的耗时", Map.of()).record(millis);
    }

    /**
     * 等待空闲 Tablet 写入者的时间。
     *
     * <p><b>持续大于零即说明 {@code write-concurrency} 不足</b> —— 这是该阈值唯一的直接判据，
     * 也是生产上的饱和度预警。2026-08-09 实测：`max-poll-records` 从 500 提到 4000 之前，
     * 8 个写入者中只有 1 个被用到，该指标恒为 0 反而说明并发根本没被触发。
     */

    // ────────────────────────── Redis ──────────────────────────

    @Override
    public void redisRoundtrip(String script, long millis) {
        gauge("iot_redis_roundtrip_ms", "Redis 往返耗时", tags("script", script)).record(millis);
    }

    @Override
    public void redisBatchSize(int size) {
        gauge("iot_redis_batch_size", "单次 pipeline 的命令数", Map.of()).record(size);
    }

    @Override
    public void redisError(String kind) {
        counter("iot_redis_error_total", "Redis 调用失败计数", tags("kind", kind)).record(1);
    }

    // ────────────────────────── 在线状态 ──────────────────────────

    /**
     * 续租三层削峰的第一层：进程内节流。
     *
     * <p>{@code hit=true} 表示被节流拦下、零 Redis 交互。命中率是
     * {@code renew-throttle-ms} / {@code renew-throttle-max-entries} 的直接判据 ——
     * 命中率低说明节流窗口或容量不足，续租正在白白打 Redis。
     */
    public void onlineRenewThrottle(boolean hit) {
        // 每消息级调用：sensor 已在构造期解析，此处只做一次字段读 + record，
        // 不再建 Map、不再拼 sensor 名、不再查 ConcurrentHashMap（H19）
        (hit ? renewThrottledSensor : renewPassedSensor).record(1);
    }

    /**
     * 续租攒批的真实批大小与落地耗时 —— {@code renew-flush-batch-size} 的判据。
     */
    public void onlineRenewFlush(int size, long millis) {
        gauge("iot_online_renew_flush_size", "单次续租批的条数", Map.of()).record(size);
        gauge("iot_online_renew_flush_ms", "单次续租批的落地耗时", Map.of()).record(millis);
    }

    /**
     * 跳变端到端延迟：Lua 侧 {@code XADD} 的 {@code changedAt} 到本实例落库的间隔。
     *
     * <p><b>这是 H2 修复效果的常驻观测</b>。此前每次都要手写
     * {@code select avg(unix_timestamp(update_time)*1000 - status_event_ms)} 才能算出来；
     * 旧实现（每 shard 一次阻塞读）的下界是 32 秒，修复后由处理速度决定。
     */
    public void onlineTransitionLag(long millis) {
        gauge("iot_online_transition_lag_ms", "跳变从 XADD 到落库的端到端延迟", Map.of()).record(millis);
    }

    /**
     * 单次排空批的条数与耗时 —— {@code transition-read-batch-size} 的判据。
     */
    public void onlineTransitionBatch(int size, long millis) {
        gauge("iot_online_transition_batch_size", "单次 transition 落库批的条数", Map.of()).record(size);
        gauge("iot_online_transition_batch_ms", "单次 transition 落库批的耗时", Map.of()).record(millis);
    }

    /**
     * PEL 积压条数（巡检环按轮汇总，<b>不按 shard 打标签</b> —— 256 个 shard 会炸基数）。
     * {@code max-pending-transitions-alert} 的判据：正常波动上界。
     */
    public void onlinePendingTransitions(long total) {
        gauge("iot_online_pending_transitions", "全部自有 shard 的 PEL 积压合计", Map.of()).record(total);
    }

    /**
     * 单 shard 扫描耗时。{@code scan-batch-size} / {@code scan-shard-concurrency} 的判据是
     * <b>它与扫描锁 TTL 的比值</b> —— 逼近 TTL 意味着锁可能在扫描途中过期。
     */
    public void onlineScanShard(long millis) {
        gauge("iot_online_scan_shard_ms", "单 shard 判活扫描耗时", Map.of()).record(millis);
    }

    // ────────────────────────── 元数据 ──────────────────────────

    /**
     * 一轮收敛完成，按 {@code mode} 分开记 —— <b>A5 的核心观测</b>。
     *
     * <p>A5 的未决问题是：{@code max-incremental-scopes} 越界会触发全量重建，
     * 而等待方只等 {@code unknown-device-reconcile-timeout-ms}。要给这两个值定值，
     * 必须先回答「{@code mode=full} 的耗时是否显著小于该超时」——
     * 没有这条指标，该问题只能停留在推测。
     */
    @Override
    public void metadataConverge(String mode, long millis, int scopes) {
        gauge("iot_metadata_converge_ms", "单轮元数据收敛耗时", tags("mode", mode)).record(millis);
        counter("iot_metadata_converge_total", "收敛轮次", tags("mode", mode)).record(1);
        if (scopes > 0) {
            gauge("iot_metadata_scopes_per_converge", "单轮涉及的 scope 数", Map.of()).record(scopes);
        }
    }

    @Override
    public void metadataCatalogEntries(long count) {
        gauge("iot_metadata_catalog_entries", "设备目录条目数", Map.of()).record(count);
    }

    @Override
    public void metadataDbFallback(int batchSize, long millis) {
        gauge("iot_metadata_db_fallback_size", "单次 MySQL 回源的设备数", Map.of()).record(batchSize);
        gauge("iot_metadata_db_fallback_ms", "单次 MySQL 回源耗时", Map.of()).record(millis);
    }

    // ────────────────────────── 装配 ──────────────────────────

    private Sensor counter(String name, String description, Map<String, String> tags) {
        return sensor(name, tags, () -> {
            Sensor s = metrics.sensor(sensorName(name, tags));
            s.add(metrics.metricName(name, GROUP, description, tags), new CumulativeCount());
            return s;
        });
    }

    /**
     * 均值 + 最大值两条。刻意不做百分位：Kafka 的 {@code Percentiles} 需要预设值域上界，
     * 猜错会静默截断，而这些量的真实分布正是压测要测的东西。
     * 需要百分位时用 profiler 或 JFR，不在这里近似。
     */
    private Sensor gauge(String name, String description, Map<String, String> tags) {
        return sensor(name, tags, () -> {
            Sensor s = metrics.sensor(sensorName(name, tags));
            s.add(metrics.metricName(name + "_avg", GROUP, description + "（均值）", tags), new Avg());
            s.add(metrics.metricName(name + "_max", GROUP, description + "（最大值）", tags), new Max());
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

    private static Map<String, String> tags(String k1, String v1, String k2, String v2) {
        Map<String, String> map = new LinkedHashMap<>(4);
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /**
     * 共享 Kafka {@link Metrics} 注册表，供 {@code RouteMetrics} 等同域指标注册。
     */
    public Metrics registry() {
        return metrics;
    }

    /**
     * 仅供审计与自检：当前已注册的指标数。
     */
    public int registeredMetricCount() {
        return metrics.metrics().size();
    }

    @Override
    public void close() {
        metrics.close();
    }

    /**
     * 供需要按 {@link MetricName} 反查的场景（当前仅测试/诊断用）。
     */
    public Map<MetricName, ? extends org.apache.kafka.common.Metric> snapshot() {
        return metrics.metrics();
    }
}

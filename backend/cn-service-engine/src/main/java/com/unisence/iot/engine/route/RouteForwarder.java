package com.unisence.iot.engine.route;

import com.unisence.iot.message.DeviceMessage;
import com.unisence.iot.metadata.RouteTarget;
import com.unisence.iot.metadata.RuleSnapshot;
import com.unisence.iot.rule.config.OutputFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * 透传路由转发器：把本批<b>已确认落库</b>的消息按快照中的路由目标原样发到 Kafka。
 *
 * <p>调用时机固定在 ingestion verticle 的 {@code handleBatch} 内、时序库写入正常返回之后、
 * {@code commitSync} 之前。语义 at-least-once：本方法抛出即整批回退重放，已 ack 的记录会重复发送，
 * 由下游按 {@code msgId} 或 {@code (deviceCode, occurredAt)} 幂等。
 *
 * <h2>热路径约束</h2>
 * <ul>
 *   <li>每条候选只查一次 {@code rules.routeTargetsFor(productKey, messageType)}；为空直接跳过；</li>
 *   <li>{@code MESSAGEPACK} 目标 value 直接引用 {@code record.value()}，不解码不拷贝；</li>
 *   <li>{@code JSON} 目标每条消息最多编码一次，多个 JSON 目标共享同一数组；</li>
 *   <li>上行 headers 直接传给 {@code ProducerRecord}，由 Kafka 客户端完成必需的一次容器复制；随后追加
 *       {@code schemaVersion / ruleKind=ROUTE / ruleId} 三个缓存的不可变 Header 对象；</li>
 *   <li>key / timestamp 原样取上行 record；partition 为 null；</li>
 *   <li>先全部 {@code send}，再恰好一次 {@code flush()}，再逐个 {@code Future.get()}；没有任何目标时不 flush；</li>
 *   <li>不新建线程、不并行化编码。</li>
 * </ul>
 */
public final class RouteForwarder implements AutoCloseable {

    private static final String HEADER_SCHEMA_VERSION = "schemaVersion";
    private static final String HEADER_RULE_KIND = "ruleKind";
    private static final String HEADER_RULE_ID = "ruleId";
    private static final byte[] SCHEMA_VERSION_BYTES = "1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RULE_KIND_ROUTE_BYTES = "ROUTE".getBytes(StandardCharsets.UTF_8);
    private static final Header SCHEMA_VERSION_HEADER =
        new RecordHeader(HEADER_SCHEMA_VERSION, SCHEMA_VERSION_BYTES);
    private static final Header RULE_KIND_ROUTE_HEADER =
        new RecordHeader(HEADER_RULE_KIND, RULE_KIND_ROUTE_BYTES);

    private final KafkaProducer<String, byte[]> producer;
    private final RouteJsonEncoder jsonEncoder;
    private final RouteMetrics metrics;
    /** 同一规则快照内按预编码 byte[] 身份复用不可变 ruleId Header。 */
    private final Map<byte[], Header> ruleIdHeaders = new IdentityHashMap<>();
    private RuleSnapshot cachedRules;

    /**
     * @param producer    与 DLQ 共用的幂等 producer；本类不负责关闭
     * @param jsonEncoder canonical JSON 编码器
     * @param metrics     透传指标
     */
    public RouteForwarder(KafkaProducer<String, byte[]> producer, RouteJsonEncoder jsonEncoder, RouteMetrics metrics) {
        this.producer = producer;
        this.jsonEncoder = jsonEncoder;
        this.metrics = metrics;
    }

    /**
     * @param rules      本批统一使用的规则快照（每批只取一次）
     * @param candidates 本批已落库、可能命中路由的消息；允许为空列表
     * @throws RouteForwardException 任一目标发送失败或 ack 超时；携带 topic / msgId / ruleId
     */
    public void forward(RuleSnapshot rules, List<RouteCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        if (cachedRules != rules) {
            cachedRules = rules;
            ruleIdHeaders.clear();
        }
        List<PendingSend> pending = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            RouteCandidate candidate = candidates.get(i);
            DeviceMessage message = candidate.message();
            List<RouteTarget> targets = rules.routeTargetsFor(message.productKey(), message.messageType());
            if (targets == null || targets.isEmpty()) {
                continue;
            }
            ConsumerRecord<String, byte[]> record = candidate.record();
            byte[] jsonValue = null;
            for (int t = 0; t < targets.size(); t++) {
                RouteTarget target = targets.get(t);
                byte[] value;
                if (target.format() == OutputFormat.MESSAGEPACK) {
                    value = record.value();
                } else {
                    if (jsonValue == null) {
                        jsonValue = jsonEncoder.encode(message);
                        metrics.jsonEncoded();
                    }
                    value = jsonValue;
                }
                byte[] ruleIdBytes = target.ruleIdHeaderBytes();
                ProducerRecord<String, byte[]> outgoing = new ProducerRecord<>(
                    target.targetTopic(), null, record.timestamp(), record.key(), value, record.headers());
                // Kafka 4.3.1 ProducerRecord 构造器已经复制了上行 headers；直接向这份必需的内部容器追加，
                // 避免在调用方再构造 base/copy。Header 对象只读，可跨 record 复用。
                Headers headers = outgoing.headers();
                headers.add(SCHEMA_VERSION_HEADER);
                headers.add(RULE_KIND_ROUTE_HEADER);
                headers.add(ruleIdHeader(ruleIdBytes));
                try {
                    pending.add(new PendingSend(
                        producer.send(outgoing), target.targetTopic(), message.msgId(), target.ruleId(), value.length));
                } catch (RuntimeException e) {
                    metrics.forwardFailed(target.targetTopic());
                    throw new RouteForwardException(target.targetTopic(), message.msgId(), target.ruleId(), e);
                }
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            producer.flush();
            for (int i = 0; i < pending.size(); i++) {
                PendingSend send = pending.get(i);
                try {
                    send.future.get();
                    metrics.forwarded(send.topic, send.valueBytes);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    metrics.forwardFailed(send.topic);
                    throw new RouteForwardException(send.topic, send.msgId, send.ruleId, e);
                } catch (Exception e) {
                    metrics.forwardFailed(send.topic);
                    throw new RouteForwardException(send.topic, send.msgId, send.ruleId, e);
                }
            }
        } catch (RouteForwardException e) {
            throw e;
        } catch (RuntimeException e) {
            PendingSend first = pending.get(0);
            metrics.forwardFailed(first.topic);
            throw new RouteForwardException(first.topic, first.msgId, first.ruleId, e);
        } finally {
            metrics.flushCompleted((System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    /**
     * 仅释放自身引用；producer 由 verticle 在 poll 线程 join 之后关闭。
     */
    @Override
    public void close() {
        cachedRules = null;
        ruleIdHeaders.clear();
    }

    private Header ruleIdHeader(byte[] ruleIdBytes) {
        Header cached = ruleIdHeaders.get(ruleIdBytes);
        if (cached != null) {
            return cached;
        }
        Header created = new RecordHeader(HEADER_RULE_ID, ruleIdBytes);
        ruleIdHeaders.put(ruleIdBytes, created);
        return created;
    }

    private record PendingSend(Future<RecordMetadata> future, String topic, String msgId, long ruleId, int valueBytes) {
    }
}

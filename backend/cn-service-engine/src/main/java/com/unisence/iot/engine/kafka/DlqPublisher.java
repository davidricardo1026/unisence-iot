package com.unisence.iot.engine.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * 死信投递（architecture-guide.md 轨道一第 3 条）。
 *
 * <p>校验/解析失败的消息一律进 {@code iot.raw.dlq}，绝不阻塞消费循环 —— 毒消息卡住分区
 * 比丢一条消息危险得多。原始 value 原样保留，判定结果放在 header，便于回放时不必重新解码。
 *
 * <p>header 中<b>必须带消费组标识</b>：存储链路与规则 topology 各自直接订阅上行 Topic，
 * 同一条毒消息会被两条链路各投一次 DLQ，没有 {@code group} 就无法区分是谁投的
 * （message-idempotency-design.md §一）。
 */
@Slf4j
public class DlqPublisher implements AutoCloseable {

    public static final String HEADER_REASON = "reason";
    public static final String HEADER_GROUP = "group";
    public static final String HEADER_SOURCE_TOPIC = "src-topic";
    public static final String HEADER_SOURCE_PARTITION = "src-partition";
    public static final String HEADER_SOURCE_OFFSET = "src-offset";

    private final KafkaProducer<String, byte[]> producer;
    private final String dlqTopic;
    private final String groupId;

    /**
     * @param producer 与透传转发共用的幂等 producer；本类不负责关闭
     */
    public DlqPublisher(KafkaProducer<String, byte[]> producer, String dlqTopic, String groupId) {
        this.producer = producer;
        this.dlqTopic = dlqTopic;
        this.groupId = groupId;
    }

    /**
     * 投递一条死信。业务判定分支逐条调用，数量与业务错误率同阶，不构成洪峰。
     *
     * @param reason 判定原因，取 {@code MessageErrorCode} 名或入口自有的路由错误码
     */
    public void publish(ConsumerRecord<String, byte[]> source, String reason) {
        publishAll(List.of(new DeadLetter(source, reason)));
    }

    /**
     * 批量投递：<b>先全部 send() 再统一等待</b>，让 producer 自行合并成少数几次请求。
     *
     * <p>逐条 {@code send().get()} 会把 N 条毒消息退化为 N 次串行往返，且完全废掉
     * producer 的 batching —— 而 DLQ 洪峰恰恰发生在「某个 driver 发错格式」这类整批失败
     * 场景，正是最不该串行的时候。
     *
     * <p><b>语义不变</b>：仍然全部确认后才返回，调用方据此在 offset 提交<b>之前</b>
     * 确认死信已落盘。commit 之后才发现 DLQ 发送失败，这条消息就彻底消失了。
     *
     * @throws IllegalStateException 任一条失败即抛出，调用方据此放弃提交 offset
     */
    public void publishAll(List<DeadLetter> deadLetters) {
        if (deadLetters.isEmpty()) {
            return;
        }
        List<Future<RecordMetadata>> futures = new ArrayList<>(deadLetters.size());
        for (DeadLetter dead : deadLetters) {
            futures.add(producer.send(toRecord(dead.record(), dead.reason())));
        }
        for (int i = 0; i < futures.size(); i++) {
            ConsumerRecord<String, byte[]> source = deadLetters.get(i).record();
            String reason = deadLetters.get(i).reason();
            try {
                futures.get(i).get();
                // 逐条降为 debug：本条信息在 DLQ 记录里已完整留证（payload + reason header），
                // 并已按 reason 计入 iot_ingress_dlq_total。日志逐条再打是第三份拷贝，
                // 却是唯一会把磁盘写满的那份（2026-08-09 实测 4 分钟 36MB）。
                // 需要逐条定位时开 debug 即可。
                log.debug("消息进入 DLQ: reason={} topic={} partition={} offset={}",
                         reason, source.topic(), source.partition(), source.offset());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DLQ 投递被中断", e);
            } catch (Exception e) {
                log.error("DLQ 投递失败，本批不提交 offset: reason={} topic={} partition={} offset={}",
                          reason, source.topic(), source.partition(), source.offset(), e);
                throw new IllegalStateException("DLQ 投递失败", e);
            }
        }
    }

    private ProducerRecord<String, byte[]> toRecord(ConsumerRecord<String, byte[]> source, String reason) {
        ProducerRecord<String, byte[]> record =
            new ProducerRecord<>(dlqTopic, source.key(), source.value());
        record.headers()
            .add(HEADER_REASON, bytes(reason))
            .add(HEADER_GROUP, bytes(groupId))
            .add(HEADER_SOURCE_TOPIC, bytes(source.topic()))
            .add(HEADER_SOURCE_PARTITION, bytes(Integer.toString(source.partition())))
            .add(HEADER_SOURCE_OFFSET, bytes(Long.toString(source.offset())));
        source.headers().forEach(h -> record.headers().add(h));
        return record;
    }

    /**
     * 一条待投递的死信。定义在此而非 verticle 内，因为批量入口需要它作为公开签名的一部分。
     */
    public record DeadLetter(ConsumerRecord<String, byte[]> record, String reason) {
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
    }
}

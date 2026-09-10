package com.unisence.iot.driver.common.kafka;

import com.unisence.iot.message.IotMessage;
import com.unisence.iot.message.codec.MessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 南向原始数据发布（device-message-contract.md §九）。
 *
 * <p>只接收类型化的 {@link IotMessage}，<b>不提供裸 String payload 重载</b>：
 * 消息结构一旦能由各驱动自行拼装，五类消息的字段约束在南向侧就没有任何强制力。
 * 现在 msgId 缺失、values 空 map、子设备漏 gatewayCode 都在<b>构造 record 时</b>就抛。
 *
 * <p>分区键取 {@link IotMessage#partitionKey()}，保证同设备有序进入同一分区；
 * 消息类型走 record header {@code mt}，使消费方能在解码 payload 前完成路由与限流。
 */
@Component
public class DeviceDataPublisher {

    /**
     * Kafka record header：消息类型。与 {@code MessageCodec.decode} 的第一个入参同源。
     */
    public static final String HEADER_MESSAGE_TYPE = "mt";

    private static final Logger log = LoggerFactory.getLogger(DeviceDataPublisher.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final MessageCodec messageCodec;
    private final String rawDataTopic;
    private final String eventTopic;

    public DeviceDataPublisher(KafkaTemplate<String, byte[]> kafkaTemplate,
                               MessageCodec messageCodec,
                               DriverKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.messageCodec = messageCodec;
        this.rawDataTopic = requireTopic(kafkaProperties.getRawDataTopic(), "raw-data-topic");
        this.eventTopic = requireTopic(kafkaProperties.getEventTopic(), "event-topic");
    }

    /**
     * 发布一条上行消息。
     *
     * <p>{@code msgId} 由调用方在消息<b>首次</b>进入平台时生成，协议重传必须复用同一值
     * （message-idempotency-design.md §四）—— 本类刻意不代为生成，那会诱导每次重试产生新 msgId，
     * 正是去重契约明令禁止的。
     */
    public CompletableFuture<SendResult<String, byte[]>> publish(IotMessage message) {
        String topic = switch (message.messageType()) {
            case PROPERTY -> rawDataTopic;
            case DEVICE_CREATE, EVENT, DEVICE_HEARTBEAT, SERVICE_HEARTBEAT -> eventTopic;
        };
        Message<byte[]> record = MessageBuilder
            .withPayload(messageCodec.encode(message))
            .setHeader(KafkaHeaders.TOPIC, topic)
            .setHeader(KafkaHeaders.KEY, message.partitionKey())
            .setHeader(HEADER_MESSAGE_TYPE, message.messageType().code())
            .build();
        CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(record);
        future.whenComplete((result, error) -> {
            if (error == null) {
                log.debug("已发布上行消息: type={} key={} msgId={} topic={} partition={} offset={}",
                          message.messageType().code(), message.partitionKey(), message.msgId(), topic,
                          result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            } else {
                log.error("上行消息发布失败: type={} key={} msgId={} topic={} errorClass={}",
                          message.messageType().code(), message.partitionKey(), message.msgId(), topic,
                          error.getClass().getName(), error);
            }
        });
        return future;
    }

    private static String requireTopic(String topic, String key) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("app.driver.kafka." + key + " 不能为空");
        }
        return topic;
    }
}

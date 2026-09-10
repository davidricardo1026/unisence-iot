package com.unisence.iot.engine.kafka;

import com.unisence.iot.engine.config.EngineRouteProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * engine 内唯一的 {@link KafkaProducer} 构造入口。
 *
 * <p>每个 ingestion verticle 实例持有一个 producer，DLQ 发布与透传转发共用它；
 * producer 的生命周期归 verticle：在 poll 线程 join 之后、写入池关闭之后最后关闭。
 *
 * <p>固定配置：{@code acks=all}、{@code enable.idempotence=true}、{@code key.serializer=StringSerializer}、
 * {@code value.serializer=ByteArraySerializer}；{@code linger.ms / compression.type / request.timeout.ms /
 * delivery.timeout.ms} 取自 {@link EngineRouteProperties}。不使用事务。
 */
public final class EngineProducerFactory {

    private EngineProducerFactory() {
    }

    /**
     * @param bootstrapServers Kafka 接入点，与 ingress 共用
     * @param clientId         {@code engine-<topic>-<ordinal>}，便于 broker 侧按实例定位
     * @param props            透传 producer 参数
     */
    public static KafkaProducer<String, byte[]> create(String bootstrapServers, String clientId,
                                                       EngineRouteProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.LINGER_MS_CONFIG, props.lingerMs());
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, props.compressionType());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, props.requestTimeoutMs());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, props.deliveryTimeoutMs());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaProducer<>(config);
    }
}

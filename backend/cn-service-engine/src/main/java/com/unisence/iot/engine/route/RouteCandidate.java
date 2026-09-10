package com.unisence.iot.engine.route;

import com.unisence.iot.message.DeviceMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 一条<b>已确认写入时序库</b>、待按透传路由转发的上行消息。
 *
 * <p>只能由 ingestion verticle 在写入器正常返回之后构造：属性链路取 {@code writeOrIsolate} 的返回集合，
 * 事件链路取进入 {@code eventRows} 且 {@code write} 成功的事件。解码失败、未注册设备、校验失败、
 * 隔离进 DLQ 的消息以及心跳/设备创建永远不得出现在这里。
 *
 * @param record  上行 Kafka record；{@code value()} 在 MESSAGEPACK 目标下原样转发，{@code key()} / {@code headers()} /
 *                {@code timestamp()} 原样保留
 * @param message 解码后的设备消息，供 JSON 编码与路由索引取 productKey / messageType
 */
public record RouteCandidate(ConsumerRecord<String, byte[]> record, DeviceMessage message) {
}

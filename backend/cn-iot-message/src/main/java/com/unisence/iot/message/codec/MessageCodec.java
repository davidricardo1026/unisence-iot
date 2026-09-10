package com.unisence.iot.message.codec;

import com.unisence.iot.message.IotMessage;

/**
 * 上行消息编解码（device-message-contract.md §五）。
 *
 * <p>线上编码为 MessagePack 带键 map + 短键；消息类型由 Kafka record header {@code mt} 路由，
 * <b>不放进 value</b>，使消费方可在解码 payload 前先完成路由与限流。
 */
public interface MessageCodec {

    /**
     * 编码为 Kafka value 字节。
     *
     * <p>调用方另取 {@link IotMessage#partitionKey()} 作 key、
     * {@code message.messageType().code()} 作 header {@code mt}。
     */
    byte[] encode(IotMessage message);

    /**
     * @param messageTypeHeader Kafka header {@code mt} 的值
     * @param value             Kafka value 字节
     * @throws MessageDecodeException 未知类型 / 未知主版本 / 结构不符 / 超长，均携带精确错误码
     */
    IotMessage decode(String messageTypeHeader, byte[] value);
}

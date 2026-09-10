package com.unisence.iot.message;

import com.unisence.iot.message.type.MessageType;

/**
 * 上行消息根类型（device-message-contract.md §二）。
 *
 * <p>密封层次 + record：消费方用 switch 模式匹配穷尽处理，
 * 将来新增第六类消息时<b>所有 switch 编译期报错</b>，而不是运行期落进 {@code default} 被静默丢弃。
 * 因此消费端 switch 不应写 {@code default} 分支 —— 那会把编译期保障重新退化成运行期沉默。
 *
 * <p>基础信封四字段<b>平铺</b>在各 record 上，属性/事件另平铺交付纪元与序号；
 * 不封装成独立的 header 记录：字段少、访问直接，
 * 代价是结构性校验必须由每个 record 的紧凑构造器显式调 {@link MessageFields#requireEnvelope}。
 *
 * <pre>{@code
 * String describe(IotMessage msg) {
 *     return switch (msg) {
 *         case DeviceCreateMessage m    -> "创建 " + m.deviceName();
 *         case DevicePropertyMessage m  -> "属性 " + m.values().keySet();
 *         case DeviceEventMessage m     -> "事件 " + m.identifier();
 *         case DeviceHeartbeatMessage m -> "设备心跳 " + m.status();
 *         case ServiceHeartbeatMessage m-> "服务心跳 " + m.serviceName();
 *     };
 * }
 * }</pre>
 */
public sealed interface IotMessage permits DeviceMessage, ServiceHeartbeatMessage {

    int MAX_MSG_ID_CHARS = 36;
    int MAX_SOURCE_CHARS = 120;

    /**
     * 线上键 {@code v}；线上编码的主版本号，解码时与当前值不等即整条拒绝。
     */
    int schemaVersion();

    /**
     * 线上键 {@code id}；驱动/网关生成的全局唯一 UUIDv7/ULID，协议重传必须复用同一值。
     */
    String msgId();

    /**
     * 线上键 {@code ts}；业务<b>发生时间</b>（epoch millis），非平台接收时间。
     */
    long occurredAt();

    /**
     * 线上键 {@code src}；驱动/网关/服务实例标识。仅用于审计与排查，
     * <b>不可信</b> —— 接入层必须用已认证连接上下文覆盖自报值。
     */
    String source();

    /**
     * 事件类型。由具体 record 类型固定返回，<b>不是可变字段</b>（契约 §2.1）。
     * 编码后写入 Kafka record header {@code mt}。
     */
    MessageType messageType();

    /**
     * Kafka 分区键。设备消息为 {@code productKey.deviceCode}，服务心跳为 {@code service.名称.实例}。
     */
    String partitionKey();
}

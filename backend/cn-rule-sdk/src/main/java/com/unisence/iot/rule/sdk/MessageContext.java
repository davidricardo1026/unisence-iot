package com.unisence.iot.rule.sdk;

import com.unisence.iot.message.type.MessageType;

import java.util.Map;
import java.util.Set;

/**
 * 规则脚本可见的只读运行上下文。
 *
 * <p>沙箱只放行本接口，不放行任何实现类。所有 payload 访问都通过类型化访问器，
 * 不暴露嵌套 Map —— {@code @CompileStatic} 下 {@code Map<String,Object>} 的链式取值无法静态解析，
 * 且类型化访问器能在缺值或类型不符时给出可定位到 identifier 的错误。
 */
public interface MessageContext {

    // ---------------- 标准信封 ----------------

    MessageEnvelope envelope();

    default int schemaVersion() {
        return envelope().schemaVersion();
    }

    default String msgId() {
        return envelope().msgId();
    }

    default String productKey() {
        return envelope().productKey();
    }

    /**
     * 服务心跳为 null。
     */
    default String deviceCode() {
        return envelope().deviceCode();
    }

    /**
     * 业务发生时间，epoch millis。
     */
    default long occurredAt() {
        return envelope().occurredAt();
    }

    default String source() {
        return envelope().source();
    }

    default MessageType messageType() {
        return envelope().messageType();
    }

    /**
     * 事件消息为事件 identifier；属性、创建与心跳为 null。
     */
    default String identifier() {
        return envelope().identifier();
    }

    // ---------------- Kafka 只读摘要 ----------------

    KafkaMeta kafka();

    default int kafkaPartition() {
        return kafka().partition();
    }

    default long kafkaOffset() {
        return kafka().offset();
    }

    default long kafkaTimestamp() {
        return kafka().timestamp();
    }

    // ---------------- 属性值（messageType == PROPERTY）----------------

    boolean hasValue(String identifier);

    Set<String> valueIdentifiers();

    /**
     * 缺失或非数值时返回 null。参与数值比较请优先用二参重载，避免拆箱 NPE。
     */
    Double numberValue(String identifier);

    /**
     * 缺失或非数值时返回 defaultValue，不抛异常。
     */
    double numberValue(String identifier, double defaultValue);

    String stringValue(String identifier);

    Boolean boolValue(String identifier);

    boolean boolValue(String identifier, boolean defaultValue);

    /**
     * 原始解码值，仅用于 output 透传；filter 中禁止基于它做链式取值。
     */
    Object rawValue(String identifier);

    /**
     * 只读视图，用于 output 整体透传属性值。
     */
    Map<String, Object> valuesView();

    // ---------------- 事件参数（messageType == EVENT）----------------

    boolean hasParam(String name);

    Set<String> paramNames();

    Double numberParam(String name);

    double numberParam(String name, double defaultValue);

    String stringParam(String name);

    Boolean boolParam(String name);

    boolean boolParam(String name, boolean defaultValue);

    Object rawParam(String name);

    Map<String, Object> paramsView();

    // ---------------- 只读快照 ----------------

    ProductSnapshot product();

    /**
     * 服务心跳为 null。
     */
    DeviceSnapshot device();

    ThingModelSnapshot thingModel();

    // ---------------- 有限工具 ----------------

    /**
     * engine 接收时刻 - occurredAt，单位毫秒；可为负（设备时钟超前）。
     */
    long ageMillis();

    /**
     * 数值区间闭区间判定；value 为 null 时返回 false，绝不抛异常。
     */
    default boolean between(Double value, double lowInclusive, double highInclusive) {
        return value != null && value >= lowInclusive && value <= highInclusive;
    }
}

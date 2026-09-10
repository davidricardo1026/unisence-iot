package com.unisence.iot.rule.sdk;

import java.util.Map;
import java.util.Set;

/**
 * {@link MessageContext} 的不可变默认实现，供 cn-service-engine 热路径与 cn-service-admin 的 compile/test 共用。
 *
 * <p>构造后 values/params/formData 均为不可变副本，脚本无法通过任何访问器改写上下文。
 *
 * @param receivedAt engine 接收时刻 epoch millis，用于 ageMillis；不使用挂钟以保证重放可确定性
 */
public record DefaultMessageContext(
    MessageEnvelope envelope,
    KafkaMeta kafka,
    Map<String, Object> values,
    Map<String, Object> params,
    ProductSnapshot product,
    DeviceSnapshot device,
    ThingModelSnapshot thingModel,
    long receivedAt) implements MessageContext {

    public DefaultMessageContext {
        values = values == null ? Map.of() : Map.copyOf(values);
        params = params == null ? Map.of() : Map.copyOf(params);
        kafka = kafka == null ? KafkaMeta.NONE : kafka;
        thingModel = thingModel == null ? ThingModelSnapshot.EMPTY : thingModel;
    }

    /**
     * 属性消息的便捷构造。
     */
    public static DefaultMessageContext ofProperty(MessageEnvelope envelope, KafkaMeta kafka,
                                                   Map<String, Object> values,
                                                   ProductSnapshot product, DeviceSnapshot device,
                                                   ThingModelSnapshot thingModel, long receivedAt) {
        return new DefaultMessageContext(envelope, kafka, values, Map.of(),
                                         product, device, thingModel, receivedAt);
    }

    /**
     * 事件消息的便捷构造。
     */
    public static DefaultMessageContext ofEvent(MessageEnvelope envelope, KafkaMeta kafka,
                                                Map<String, Object> params,
                                                ProductSnapshot product, DeviceSnapshot device,
                                                ThingModelSnapshot thingModel, long receivedAt) {
        return new DefaultMessageContext(envelope, kafka, Map.of(), params,
                                         product, device, thingModel, receivedAt);
    }

    // ---------------- 属性值 ----------------

    @Override
    public boolean hasValue(String identifier) {
        return values.containsKey(identifier);
    }

    @Override
    public Set<String> valueIdentifiers() {
        return values.keySet();
    }

    @Override
    public Double numberValue(String identifier) {
        return toNumber(values.get(identifier));
    }

    @Override
    public double numberValue(String identifier, double defaultValue) {
        Double value = toNumber(values.get(identifier));
        return value == null ? defaultValue : value;
    }

    @Override
    public String stringValue(String identifier) {
        Object raw = values.get(identifier);
        return raw instanceof String text ? text : null;
    }

    @Override
    public Boolean boolValue(String identifier) {
        return toBoolean(values.get(identifier));
    }

    @Override
    public boolean boolValue(String identifier, boolean defaultValue) {
        Boolean value = toBoolean(values.get(identifier));
        return value == null ? defaultValue : value;
    }

    @Override
    public Object rawValue(String identifier) {
        return values.get(identifier);
    }

    @Override
    public Map<String, Object> valuesView() {
        return values;
    }

    // ---------------- 事件参数 ----------------

    @Override
    public boolean hasParam(String name) {
        return params.containsKey(name);
    }

    @Override
    public Set<String> paramNames() {
        return params.keySet();
    }

    @Override
    public Double numberParam(String name) {
        return toNumber(params.get(name));
    }

    @Override
    public double numberParam(String name, double defaultValue) {
        Double value = toNumber(params.get(name));
        return value == null ? defaultValue : value;
    }

    @Override
    public String stringParam(String name) {
        Object raw = params.get(name);
        return raw instanceof String text ? text : null;
    }

    @Override
    public Boolean boolParam(String name) {
        return toBoolean(params.get(name));
    }

    @Override
    public boolean boolParam(String name, boolean defaultValue) {
        Boolean value = toBoolean(params.get(name));
        return value == null ? defaultValue : value;
    }

    @Override
    public Object rawParam(String name) {
        return params.get(name);
    }

    @Override
    public Map<String, Object> paramsView() {
        return params;
    }

    // ---------------- 工具 ----------------

    @Override
    public long ageMillis() {
        return receivedAt - envelope.occurredAt();
    }

    /**
     * 数值归一。字符串<b>不</b>参与隐式转换：上行值的类型由物模型 data_type 校验保证，
     * 若此处放行 "80" 这样的字符串，物模型类型配错会被静默掩盖。
     */
    private static Double toNumber(Object raw) {
        return raw instanceof Number number ? number.doubleValue() : null;
    }

    /**
     * 布尔归一。同样禁止 "true"/1 这类隐式转换。
     */
    private static Boolean toBoolean(Object raw) {
        return raw instanceof Boolean bool ? bool : null;
    }
}

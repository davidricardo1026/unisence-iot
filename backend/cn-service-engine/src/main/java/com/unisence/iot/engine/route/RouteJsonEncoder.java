package com.unisence.iot.engine.route;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.unisence.iot.message.DeviceEventMessage;
import com.unisence.iot.message.DeviceMessage;
import com.unisence.iot.message.DevicePropertyMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

/**
 * 上行信封的 canonical JSON 编码（{@code schemaVersion=1}），用于 {@code format=JSON} 的透传目标。
 *
 * <p>字段名与 {@code cn-iot-message} record 组件同名、缺省字段不省略：
 * {@code schemaVersion, msgId, messageType, productKey, deviceCode, occurredAt, source, deliveryEpoch, deliverySequence}，
 * 属性消息再带 {@code values}（object），事件消息再带 {@code identifier}（string）与 {@code params}（object，可为空 object）。
 * MessagePack 原生类型映射：整数/浮点 → number，布尔 → boolean，字符串 → string，二进制 → Base64 string，数组/映射递归。
 *
 * <p><b>必须直接从 {@link DeviceMessage} 流式写出字节</b>（Jackson {@code JsonGenerator} 或等价 byte 级 writer），
 * 禁止先构造 {@code JsonObject} / {@code Map} 中间对象再序列化。该形态是对外契约，语义变化即升 {@code schemaVersion}。
 */
public final class RouteJsonEncoder {

    public static final int SCHEMA_VERSION = 1;

    private final JsonFactory jsonFactory;

    public RouteJsonEncoder() {
        this.jsonFactory = new JsonFactory();
    }

    /**
     * @param message 已解码的属性或事件消息
     * @return canonical JSON 的 UTF-8 字节；同一条消息的多个 JSON 目标必须共享该数组
     * @throws IllegalArgumentException message 不是属性或事件消息
     */
    public byte[] encode(DeviceMessage message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (JsonGenerator gen = jsonFactory.createGenerator(out)) {
            gen.writeStartObject();
            gen.writeNumberField("schemaVersion", message.schemaVersion());
            gen.writeStringField("msgId", message.msgId());
            gen.writeStringField("messageType", message.messageType().code());
            gen.writeStringField("productKey", message.productKey());
            gen.writeStringField("deviceCode", message.deviceCode());
            gen.writeNumberField("occurredAt", message.occurredAt());
            gen.writeStringField("source", message.source());
            switch (message) {
                case DevicePropertyMessage property -> {
                    gen.writeNumberField("deliveryEpoch", property.deliveryEpoch());
                    gen.writeNumberField("deliverySequence", property.deliverySequence());
                    gen.writeFieldName("values");
                    writeMap(gen, property.values());
                }
                case DeviceEventMessage event -> {
                    gen.writeNumberField("deliveryEpoch", event.deliveryEpoch());
                    gen.writeNumberField("deliverySequence", event.deliverySequence());
                    gen.writeStringField("identifier", event.identifier());
                    gen.writeFieldName("params");
                    writeMap(gen, event.params());
                }
                default -> throw new IllegalArgumentException(
                    "透传 JSON 只支持属性与事件消息: " + message.messageType().code());
            }
            gen.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("透传 JSON 编码失败: msgId=" + message.msgId(), e);
        }
        return out.toByteArray();
    }

    private static void writeMap(JsonGenerator gen, Map<String, ?> map) throws IOException {
        gen.writeStartObject();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            gen.writeFieldName(entry.getKey());
            writeValue(gen, entry.getValue());
        }
        gen.writeEndObject();
    }

    private static void writeValue(JsonGenerator gen, Object value) throws IOException {
        switch (value) {
            case null -> gen.writeNull();
            case Boolean b -> gen.writeBoolean(b);
            case Integer i -> gen.writeNumber(i);
            case Long l -> gen.writeNumber(l);
            case Short s -> gen.writeNumber(s.intValue());
            case Byte b -> gen.writeNumber(b.intValue());
            case Float f -> gen.writeNumber(f);
            case Double d -> gen.writeNumber(d);
            case BigInteger bi -> gen.writeNumber(bi);
            case BigDecimal bd -> gen.writeNumber(bd);
            case String s -> gen.writeString(s);
            case byte[] bytes -> gen.writeString(Base64.getEncoder().encodeToString(bytes));
            case Map<?, ?> map -> {
                gen.writeStartObject();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    gen.writeFieldName(String.valueOf(entry.getKey()));
                    writeValue(gen, entry.getValue());
                }
                gen.writeEndObject();
            }
            case Collection<?> collection -> {
                gen.writeStartArray();
                for (Object element : collection) {
                    writeValue(gen, element);
                }
                gen.writeEndArray();
            }
            default -> throw new IllegalArgumentException(
                "不支持的 payload 值类型: " + value.getClass().getName());
        }
    }
}

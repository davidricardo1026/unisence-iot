package com.unisence.iot.metadata;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备 L2 缓存值的编解码（metadata-sync-bus.md §6.6）。
 *
 * <p>值的布局固定为「定宽 ASCII 头 + MessagePack 载荷」：
 *
 * <pre>
 * 01|0000000000000000001|0000000001|0000000001|&lt;MessagePack&gt;
 * ^协议 ^19位 deviceId    ^10位 rowVersion ^10位 formVersion
 * </pre>
 *
 * <p><b>为什么头部是定宽十进制 ASCII 而不是二进制或 JSON</b>：L2 的版本守卫写入是一段 Lua 脚本，
 * 它必须在不解析载荷的前提下比较版本。Lua 的 number 是双精度，{@code tonumber} 处理
 * {@code BIGINT} 级 deviceId 会丢精度；而<b>等长</b>的十进制字符串按字典序比较与按数值比较完全一致，
 * 因此 Lua 只做定长切片 + 字符串比较，既准确又不需要 cjson。
 *
 * <p>前导零一旦溢出（deviceId 超过 19 位）就是协议失效，此时宁可拒绝写入也不能截断。
 */
public final class DeviceCacheCodec {

    /**
     * 协议版本；与 {@link DeviceCacheEnvelope#CURRENT_SCHEMA_VERSION} 对应。
     */
    static final String PROTOCOL = "01";
    static final int DEVICE_ID_WIDTH = 19;
    static final int VERSION_WIDTH = 10;
    /**
     * {@code "01|" + 19 + "|" + 10 + "|" + 10 + "|"}
     */
    static final int HEADER_LENGTH = 2 + 1 + DEVICE_ID_WIDTH + 1 + VERSION_WIDTH + 1 + VERSION_WIDTH + 1;

    private static final String KEY_PRODUCT_KEY = "pk";
    private static final String KEY_DEVICE_CODE = "dc";
    private static final String KEY_PRODUCT_ID = "pi";
    private static final String KEY_DEVICE_NAME = "dn";
    private static final String KEY_GATEWAY_ID = "gi";
    private static final String KEY_GATEWAY_CODE = "gc";
    private static final String KEY_NODE_TYPE = "nt";
    private static final String KEY_LONGITUDE = "lo";
    private static final String KEY_LATITUDE = "la";
    private static final String KEY_FORM = "fd";

    private DeviceCacheCodec() {
    }

    /**
     * 编码为 L2 value 的字节。
     */
    public static byte[] encode(DeviceCacheEnvelope envelope) {
        DeviceRuntimeMeta value = envelope.value();
        String header = PROTOCOL + '|'
            + pad(value.deviceId(), DEVICE_ID_WIDTH) + '|'
            + pad(envelope.deviceRowVersion(), VERSION_WIDTH) + '|'
            + pad(envelope.deviceFormVersion(), VERSION_WIDTH) + '|';
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = packPayload(value);
        byte[] result = new byte[headerBytes.length + payload.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(payload, 0, result, headerBytes.length, payload.length);
        return result;
    }

    /**
     * 解码。
     *
     * @return 解析出的信封；协议版本未知或结构损坏返回 {@code null}（等价于 miss）
     */
    public static DeviceCacheEnvelope decode(byte[] raw) {
        if (raw == null || raw.length <= HEADER_LENGTH) {
            return null;
        }
        String header = new String(raw, 0, HEADER_LENGTH, StandardCharsets.US_ASCII);
        if (!header.startsWith(PROTOCOL + '|')) {
            // 未知协议版本：直接当 miss 回源，不猜测布局
            return null;
        }
        try {
            int at = 3;
            long deviceId = Long.parseLong(header, at, at + DEVICE_ID_WIDTH, 10);
            at += DEVICE_ID_WIDTH + 1;
            int rowVersion = Integer.parseInt(header, at, at + VERSION_WIDTH, 10);
            at += VERSION_WIDTH + 1;
            int formVersion = Integer.parseInt(header, at, at + VERSION_WIDTH, 10);

            DeviceRuntimeMeta value = unpackPayload(raw, deviceId);
            return new DeviceCacheEnvelope(DeviceCacheEnvelope.CURRENT_SCHEMA_VERSION,
                                           rowVersion, formVersion, value);
        } catch (RuntimeException e) {
            // 损坏条目当 miss 处理：抛出去会让整批消息失败，而回源一次就能自愈
            return null;
        }
    }

    private static byte[] packPayload(DeviceRuntimeMeta value) {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packMapHeader(10);
            packString(packer, KEY_PRODUCT_KEY, value.ref().productKey());
            packString(packer, KEY_DEVICE_CODE, value.ref().deviceCode());
            packer.packString(KEY_PRODUCT_ID).packLong(value.productId());
            packString(packer, KEY_DEVICE_NAME, value.deviceName());
            packer.packString(KEY_GATEWAY_ID);
            if (value.gatewayId() == null) {
                packer.packNil();
            } else {
                packer.packLong(value.gatewayId());
            }
            packString(packer, KEY_GATEWAY_CODE, value.gatewayCode());
            packer.packString(KEY_NODE_TYPE).packInt(value.nodeType());
            packDouble(packer, KEY_LONGITUDE, value.longitude());
            packDouble(packer, KEY_LATITUDE, value.latitude());
            packer.packString(KEY_FORM);
            packValue(packer, value.ruleVisibleFormData());
            return packer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("设备投影 MessagePack 编码失败", e);
        }
    }

    private static DeviceRuntimeMeta unpackPayload(byte[] raw, long deviceId) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(
            raw, HEADER_LENGTH, raw.length - HEADER_LENGTH)) {
            Map<String, Value> fields = new LinkedHashMap<>();
            int size = unpacker.unpackMapHeader();
            for (int i = 0; i < size; i++) {
                fields.put(unpacker.unpackString(), unpacker.unpackValue());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> formData = (Map<String, Object>) toJava(fields.get(KEY_FORM));
            return new DeviceRuntimeMeta(
                deviceId,
                fields.get(KEY_PRODUCT_ID).asIntegerValue().asLong(),
                new DeviceRef(asString(fields.get(KEY_PRODUCT_KEY)), asString(fields.get(KEY_DEVICE_CODE))),
                asString(fields.get(KEY_DEVICE_NAME)),
                asLong(fields.get(KEY_GATEWAY_ID)),
                asString(fields.get(KEY_GATEWAY_CODE)),
                fields.get(KEY_NODE_TYPE).asIntegerValue().asInt(),
                asDouble(fields.get(KEY_LONGITUDE)),
                asDouble(fields.get(KEY_LATITUDE)),
                formData == null ? Map.of() : formData);
        } catch (IOException e) {
            throw new UncheckedIOException("设备投影 MessagePack 解码失败", e);
        }
    }

    // ────────────────────────────── 打包辅助 ──────────────────────────────

    private static void packString(MessageBufferPacker packer, String key, String value) throws IOException {
        packer.packString(key);
        if (value == null) {
            packer.packNil();
        } else {
            packer.packString(value);
        }
    }

    private static void packDouble(MessageBufferPacker packer, String key, Double value) throws IOException {
        packer.packString(key);
        if (value == null) {
            packer.packNil();
        } else {
            packer.packDouble(value);
        }
    }

    /**
     * 表单值已由 {@link MetadataValueFreezer} 限定为 null/标量/Map/List，因此这里的分支是穷尽的。
     */
    private static void packValue(MessageBufferPacker packer, Object value) throws IOException {
        switch (value) {
            case null -> packer.packNil();
            case String s -> packer.packString(s);
            case Boolean b -> packer.packBoolean(b);
            case Integer i -> packer.packInt(i);
            case Long l -> packer.packLong(l);
            case Float f -> packer.packFloat(f);
            case Double d -> packer.packDouble(d);
            case Number n -> packer.packDouble(n.doubleValue());
            case Map<?, ?> map -> {
                packer.packMapHeader(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    packer.packString(String.valueOf(entry.getKey()));
                    packValue(packer, entry.getValue());
                }
            }
            case List<?> list -> {
                packer.packArrayHeader(list.size());
                for (Object item : list) {
                    packValue(packer, item);
                }
            }
            default -> throw new IllegalArgumentException("表单值出现非法类型: " + value.getClass().getName());
        }
    }

    private static Object toJava(Value value) {
        if (value == null || value.isNilValue()) {
            return null;
        }
        if (value.getValueType() == ValueType.MAP) {
            Map<String, Object> map = new LinkedHashMap<>();
            value.asMapValue().map().forEach((k, v) -> map.put(k.asStringValue().asString(), toJava(v)));
            return java.util.Collections.unmodifiableMap(map);
        }
        if (value.getValueType() == ValueType.ARRAY) {
            List<Object> list = new ArrayList<>();
            value.asArrayValue().forEach(item -> list.add(toJava(item)));
            return java.util.Collections.unmodifiableList(list);
        }
        if (value.isBooleanValue()) {
            return value.asBooleanValue().getBoolean();
        }
        if (value.isIntegerValue()) {
            return value.asIntegerValue().asLong();
        }
        if (value.isFloatValue()) {
            return value.asFloatValue().toDouble();
        }
        return value.asStringValue().asString();
    }

    private static String asString(Value value) {
        return value == null || value.isNilValue() ? null : value.asStringValue().asString();
    }

    private static Long asLong(Value value) {
        return value == null || value.isNilValue() ? null : value.asIntegerValue().asLong();
    }

    private static Double asDouble(Value value) {
        return value == null || value.isNilValue() ? null : value.asFloatValue().toDouble();
    }

    /**
     * 定宽左补零；溢出即协议失效，宁可拒绝也不截断。
     */
    private static String pad(long value, int width) {
        if (value < 0) {
            throw new IllegalArgumentException("定宽头部不接受负值: " + value);
        }
        String text = Long.toString(value);
        if (text.length() > width) {
            throw new IllegalArgumentException(
                "值超出 L2 头部定宽 " + width + " 位，协议失效: " + value);
        }
        return "0".repeat(width - text.length()) + text;
    }
}

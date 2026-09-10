package com.unisence.iot.message.codec;

import com.unisence.iot.message.*;
import com.unisence.iot.message.type.HeartbeatStatus;
import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.message.type.NodeType;
import org.msgpack.core.*;
import org.msgpack.value.ValueType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MessagePack 实现（device-message-contract.md §五）。
 *
 * <p>无状态、线程安全，可作为单例注入。每次编解码新建 packer/unpacker —— 它们持有内部缓冲，复用需自行加锁，
 * 而 MessagePack 的 packer 分配开销远低于一次 Kafka 往返，不值得为此引入锁竞争。
 */
public final class MessagePackMessageCodec implements MessageCodec {

    /**
     * 当前 schema 主版本。解码时 {@code v} 与本值不同即整条拒绝。
     */
    public static final int SCHEMA_VERSION = 2;

    // ── 信封短键 ──
    private static final String K_VERSION = "v";
    private static final String K_MSG_ID = "id";
    private static final String K_PRODUCT_KEY = "pk";
    private static final String K_DEVICE_CODE = "dc";
    private static final String K_OCCURRED_AT = "ts";
    private static final String K_SOURCE = "src";
    private static final String K_DELIVERY_EPOCH = "de";
    private static final String K_DELIVERY_SEQUENCE = "ds";
    private static final String K_PAYLOAD = "p";

    // ── payload 短键 ──
    private static final String K_DEVICE_NAME = "name";
    private static final String K_NODE_TYPE = "nt";
    private static final String K_GATEWAY_CODE = "gw";
    private static final String K_FORM_DATA = "form";
    private static final String K_VALUES = "vals";
    private static final String K_IDENTIFIER = "idf";
    private static final String K_PARAMS = "params";
    private static final String K_STATUS = "st";
    private static final String K_METRICS = "metrics";
    private static final String K_SERVICE_NAME = "svc";
    private static final String K_INSTANCE_ID = "inst";
    private static final String K_STARTED_AT = "start";
    private static final String K_VERSION_TEXT = "ver";
    private static final String K_ADDRESS = "addr";

    // PROPERTY / EVENT 热路径使用的短键编号。0 表示未知键。
    private static final int KEY_VERSION = 1;
    private static final int KEY_MSG_ID = 2;
    private static final int KEY_PRODUCT_KEY = 3;
    private static final int KEY_DEVICE_CODE = 4;
    private static final int KEY_DELIVERY_EPOCH = 5;
    private static final int KEY_DELIVERY_SEQUENCE = 6;
    private static final int KEY_OCCURRED_AT = 7;
    private static final int KEY_SOURCE = 8;
    private static final int KEY_PAYLOAD = 9;
    private static final int KEY_VALUES = 10;
    private static final int KEY_IDENTIFIER = 11;
    private static final int KEY_PARAMS = 12;
    private static final int HOT_KEY_MAX_BYTES = 6;

    @Override
    public byte[] encode(IotMessage message) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(K_VERSION, message.schemaVersion());
        root.put(K_MSG_ID, message.msgId());
        if (message instanceof DeviceMessage device) {
            root.put(K_PRODUCT_KEY, device.productKey());
            root.put(K_DEVICE_CODE, device.deviceCode());
        }
        if (message instanceof SequencedDeviceMessage sequenced) {
            root.put(K_DELIVERY_EPOCH, sequenced.deliveryEpoch());
            root.put(K_DELIVERY_SEQUENCE, sequenced.deliverySequence());
        }
        root.put(K_OCCURRED_AT, message.occurredAt());
        root.put(K_SOURCE, message.source());
        root.put(K_PAYLOAD, payloadOf(message));

        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packObject(packer, root);
            return packer.toByteArray();
        } catch (IllegalArgumentException e) {
            // 编码不出去的值类型是<b>驱动侧编程错误</b>，不是线上数据错误，因此不给它分配 4100 段错误码 ——
            // 那个区间是给 DLQ 用的。这里保持 IllegalArgumentException 并补上 msgId 定位。
            throw new IllegalArgumentException(
                "消息编码失败: msgId=" + message.msgId() + ", " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("消息编码失败: msgId=" + message.msgId(), e);
        }
    }

    /**
     * switch 无 default 分支：新增消息类型时此处编译期报错，而不是漏编码一类消息。
     */
    private static Map<String, Object> payloadOf(IotMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (message) {
            case DeviceCreateMessage create -> {
                putIfPresent(payload, K_DEVICE_NAME, create.deviceName());
                payload.put(K_NODE_TYPE, create.nodeType().code());
                putIfPresent(payload, K_GATEWAY_CODE, create.gatewayCode());
                putIfNotEmpty(payload, K_FORM_DATA, create.formData());
            }
            case DevicePropertyMessage property -> payload.put(K_VALUES, property.values());
            case DeviceEventMessage event -> {
                payload.put(K_IDENTIFIER, event.identifier());
                putIfNotEmpty(payload, K_PARAMS, event.params());
            }
            case DeviceHeartbeatMessage heartbeat -> {
                putIfPresent(payload, K_STATUS,
                             heartbeat.status() == null ? null : heartbeat.status().code());
                putIfNotEmpty(payload, K_METRICS, heartbeat.metrics());
            }
            case ServiceHeartbeatMessage service -> {
                payload.put(K_SERVICE_NAME, service.serviceName());
                payload.put(K_INSTANCE_ID, service.instanceId());
                payload.put(K_STARTED_AT, service.startedAt());
                putIfPresent(payload, K_VERSION_TEXT, service.version());
                putIfPresent(payload, K_ADDRESS, service.address());
                putIfNotEmpty(payload, K_METRICS, service.metrics());
            }
        }
        return payload;
    }

    @Override
    public IotMessage decode(String messageTypeHeader, byte[] value) {
        MessageType type = parseType(messageTypeHeader);
        if (type == MessageType.PROPERTY || type == MessageType.EVENT) {
            return decodeDataPlane(type, value);
        }
        Map<String, Object> root = unpackRoot(value);
        String msgId = asString(root.get(K_MSG_ID));

        int schemaVersion = (int) asLong(root.get(K_VERSION), 0L);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new MessageDecodeException(MessageErrorCode.SCHEMA_VERSION_UNSUPPORTED, msgId,
                                             "收到 v=" + schemaVersion + "，当前仅支持 " + SCHEMA_VERSION);
        }

        long occurredAt = asLong(root.get(K_OCCURRED_AT), 0L);
        String source = asString(root.get(K_SOURCE));
        long deliveryEpoch = asLong(root.get(K_DELIVERY_EPOCH), 0L);
        long deliverySequence = asLong(root.get(K_DELIVERY_SEQUENCE), -1L);

        try {
            Map<String, Object> payload = asMap(root.get(K_PAYLOAD));
            return switch (type) {
                case DEVICE_CREATE -> decodeCreate(schemaVersion, msgId, occurredAt, source,
                                                   pk(root), dc(root), payload);
                case PROPERTY -> new DevicePropertyMessage(schemaVersion, msgId, occurredAt, source,
                                                           deliveryEpoch, deliverySequence,
                                                           pk(root), dc(root), asMap(payload.get(K_VALUES)));
                case EVENT -> new DeviceEventMessage(schemaVersion, msgId, occurredAt, source,
                                                     deliveryEpoch, deliverySequence,
                                                     pk(root), dc(root), asString(payload.get(K_IDENTIFIER)),
                                                     asMap(payload.get(K_PARAMS)));
                case DEVICE_HEARTBEAT -> decodeHeartbeat(schemaVersion, msgId, occurredAt, source,
                                                         pk(root), dc(root), payload);
                case SERVICE_HEARTBEAT -> decodeServiceHeartbeat(schemaVersion, msgId, occurredAt,
                                                                 source, payload);
            };
        } catch (MessageStructureException e) {
            // 保留 record 构造器判定的精确错误码，不降级成笼统的 PAYLOAD_DECODE_FAILED
            throw new MessageDecodeException(e.errorCode(), msgId, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, msgId,
                                             e.getMessage(), e);
        }
    }

    /**
     * 属性/事件是规则链路的数据面热路径。直接读取带键 map，避免先建立随后立即丢弃的 root/payload 对象树。
     */
    private static IotMessage decodeDataPlane(MessageType type, byte[] value) {
        if (value == null || value.length == 0) {
            throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, null,
                                             "Kafka value 为空");
        }

        DataPlaneFields fields = new DataPlaneFields();
        byte[] keyScratch = new byte[HOT_KEY_MAX_BYTES];
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(value)) {
            if (unpacker.getNextFormat().getValueType() != ValueType.MAP) {
                throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, null,
                                                 "消息根节点必须是 map，实际 "
                                                     + unpacker.getNextFormat().getValueType());
            }
            int size = unpacker.unpackMapHeader();
            for (int i = 0; i < size; i++) {
                int key = readHotKey(unpacker, keyScratch);
                switch (key) {
                    case KEY_VERSION -> fields.schemaVersion = (int) readLongValue(unpacker, 0L);
                    case KEY_MSG_ID -> fields.msgId = readStringValue(unpacker);
                    case KEY_PRODUCT_KEY -> fields.productKey = readStringValue(unpacker);
                    case KEY_DEVICE_CODE -> fields.deviceCode = readStringValue(unpacker);
                    case KEY_DELIVERY_EPOCH -> fields.deliveryEpoch = readLongValue(unpacker, 0L);
                    case KEY_DELIVERY_SEQUENCE -> fields.deliverySequence = readLongValue(unpacker, -1L);
                    case KEY_OCCURRED_AT -> fields.occurredAt = readLongValue(unpacker, 0L);
                    case KEY_SOURCE -> fields.source = readStringValue(unpacker);
                    case KEY_PAYLOAD -> readDataPlanePayload(unpacker, type, fields, keyScratch);
                    default -> readValue(unpacker);
                }
            }
        } catch (MessageDecodeException e) {
            throw e;
        } catch (IOException | MessagePackException | IllegalArgumentException e) {
            throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, fields.msgId,
                                             "MessagePack 解码失败", e);
        }

        if (fields.schemaVersion != SCHEMA_VERSION) {
            throw new MessageDecodeException(MessageErrorCode.SCHEMA_VERSION_UNSUPPORTED, fields.msgId,
                                             "收到 v=" + fields.schemaVersion + "，当前仅支持 " + SCHEMA_VERSION);
        }

        try {
            return switch (type) {
                case PROPERTY -> new DevicePropertyMessage(
                    fields.schemaVersion, fields.msgId, fields.occurredAt, fields.source,
                    fields.deliveryEpoch, fields.deliverySequence, fields.productKey, fields.deviceCode,
                    fields.values);
                case EVENT -> new DeviceEventMessage(
                    fields.schemaVersion, fields.msgId, fields.occurredAt, fields.source,
                    fields.deliveryEpoch, fields.deliverySequence, fields.productKey, fields.deviceCode,
                    fields.identifier, fields.params);
                default -> throw new IllegalStateException("非数据面消息进入专用解码: " + type);
            };
        } catch (MessageStructureException e) {
            throw new MessageDecodeException(e.errorCode(), fields.msgId, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, fields.msgId,
                                             e.getMessage(), e);
        }
    }

    private static void readDataPlanePayload(MessageUnpacker unpacker, MessageType type,
                                             DataPlaneFields fields, byte[] keyScratch) throws IOException {
        ValueType payloadType = unpacker.getNextFormat().getValueType();
        if (payloadType == ValueType.NIL) {
            unpacker.unpackNil();
            if (type == MessageType.PROPERTY) {
                fields.values = Map.of();
            } else {
                fields.identifier = null;
                fields.params = Map.of();
            }
            return;
        }
        if (payloadType != ValueType.MAP) {
            throw new IllegalArgumentException("期望 map，实际 " + readValue(unpacker).getClass().getSimpleName());
        }

        Map<String, Object> values = Map.of();
        String identifier = null;
        Map<String, Object> params = Map.of();
        int size = unpacker.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            int key = readHotKey(unpacker, keyScratch);
            if (type == MessageType.PROPERTY && key == KEY_VALUES) {
                values = readMapValue(unpacker);
            } else if (type == MessageType.EVENT && key == KEY_IDENTIFIER) {
                identifier = readStringValue(unpacker);
            } else if (type == MessageType.EVENT && key == KEY_PARAMS) {
                params = readMapValue(unpacker);
            } else {
                readValue(unpacker);
            }
        }
        if (type == MessageType.PROPERTY) {
            fields.values = values;
        } else {
            fields.identifier = identifier;
            fields.params = params;
        }
    }

    private static Map<String, Object> readMapValue(MessageUnpacker unpacker) throws IOException {
        ValueType type = unpacker.getNextFormat().getValueType();
        if (type == ValueType.NIL) {
            unpacker.unpackNil();
            return Map.of();
        }
        if (type != ValueType.MAP) {
            return asMap(readValue(unpacker));
        }
        int size = unpacker.unpackMapHeader();
        Map<String, Object> map = LinkedHashMap.newLinkedHashMap(size);
        for (int i = 0; i < size; i++) {
            Object key = readValue(unpacker);
            map.put(key == null ? null : String.valueOf(key), readValue(unpacker));
        }
        return map;
    }

    private static long readLongValue(MessageUnpacker unpacker, long defaultValue) throws IOException {
        return switch (unpacker.getNextFormat().getValueType()) {
            case NIL -> {
                unpacker.unpackNil();
                yield defaultValue;
            }
            case INTEGER -> unpacker.unpackLong();
            case FLOAT -> (long) unpacker.unpackDouble();
            default -> asLong(readValue(unpacker), defaultValue);
        };
    }

    private static String readStringValue(MessageUnpacker unpacker) throws IOException {
        ValueType type = unpacker.getNextFormat().getValueType();
        if (type == ValueType.NIL) {
            unpacker.unpackNil();
            return null;
        }
        if (type != ValueType.STRING) {
            return asString(readValue(unpacker));
        }
        int len = unpacker.unpackRawStringHeader();
        return len == 0 ? "" : new String(unpacker.readPayload(len), StandardCharsets.UTF_8);
    }

    private static int readHotKey(MessageUnpacker unpacker, byte[] scratch) throws IOException {
        if (unpacker.getNextFormat().getValueType() != ValueType.STRING) {
            Object key = readValue(unpacker);
            return hotKeyCode(key == null ? null : String.valueOf(key));
        }
        int len = unpacker.unpackRawStringHeader();
        if (len > scratch.length) {
            unpacker.readPayload(len);
            return 0;
        }
        unpacker.readPayload(scratch, 0, len);
        return hotKeyCode(scratch, len);
    }

    private static int hotKeyCode(byte[] key, int len) {
        return switch (len) {
            case 1 -> key[0] == 'v' ? KEY_VERSION : key[0] == 'p' ? KEY_PAYLOAD : 0;
            case 2 -> key[0] == 'i' && key[1] == 'd' ? KEY_MSG_ID
                : key[0] == 'p' && key[1] == 'k' ? KEY_PRODUCT_KEY
                : key[0] == 'd' && key[1] == 'c' ? KEY_DEVICE_CODE
                : key[0] == 'd' && key[1] == 'e' ? KEY_DELIVERY_EPOCH
                : key[0] == 'd' && key[1] == 's' ? KEY_DELIVERY_SEQUENCE
                : key[0] == 't' && key[1] == 's' ? KEY_OCCURRED_AT : 0;
            case 3 -> key[0] == 's' && key[1] == 'r' && key[2] == 'c' ? KEY_SOURCE
                : key[0] == 'i' && key[1] == 'd' && key[2] == 'f' ? KEY_IDENTIFIER : 0;
            case 4 -> key[0] == 'v' && key[1] == 'a' && key[2] == 'l' && key[3] == 's'
                ? KEY_VALUES : 0;
            case 6 -> key[0] == 'p' && key[1] == 'a' && key[2] == 'r' && key[3] == 'a'
                && key[4] == 'm' && key[5] == 's' ? KEY_PARAMS : 0;
            default -> 0;
        };
    }

    private static int hotKeyCode(String key) {
        if (key == null) {
            return 0;
        }
        return switch (key) {
            case K_VERSION -> KEY_VERSION;
            case K_MSG_ID -> KEY_MSG_ID;
            case K_PRODUCT_KEY -> KEY_PRODUCT_KEY;
            case K_DEVICE_CODE -> KEY_DEVICE_CODE;
            case K_DELIVERY_EPOCH -> KEY_DELIVERY_EPOCH;
            case K_DELIVERY_SEQUENCE -> KEY_DELIVERY_SEQUENCE;
            case K_OCCURRED_AT -> KEY_OCCURRED_AT;
            case K_SOURCE -> KEY_SOURCE;
            case K_PAYLOAD -> KEY_PAYLOAD;
            case K_VALUES -> KEY_VALUES;
            case K_IDENTIFIER -> KEY_IDENTIFIER;
            case K_PARAMS -> KEY_PARAMS;
            default -> 0;
        };
    }

    private static final class DataPlaneFields {
        private int schemaVersion;
        private String msgId;
        private String productKey;
        private String deviceCode;
        private long deliveryEpoch;
        private long deliverySequence = -1L;
        private long occurredAt;
        private String source;
        private Map<String, Object> values = Map.of();
        private String identifier;
        private Map<String, Object> params = Map.of();
    }

    private static DeviceCreateMessage decodeCreate(int schemaVersion, String msgId, long occurredAt,
                                                    String source, String productKey, String deviceCode,
                                                    Map<String, Object> payload) {
        Object rawNodeType = payload.get(K_NODE_TYPE);
        if (rawNodeType == null) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                "nodeType", "device_create 必须携带 nt");
        }
        return new DeviceCreateMessage(schemaVersion, msgId, occurredAt, source, productKey, deviceCode,
                                       asString(payload.get(K_DEVICE_NAME)),
                                       NodeType.fromCode((int) asLong(rawNodeType, 0L)),
                                       asString(payload.get(K_GATEWAY_CODE)),
                                       asMap(payload.get(K_FORM_DATA)));
    }

    private static DeviceHeartbeatMessage decodeHeartbeat(int schemaVersion, String msgId,
                                                          long occurredAt, String source,
                                                          String productKey, String deviceCode,
                                                          Map<String, Object> payload) {
        String rawStatus = asString(payload.get(K_STATUS));
        return new DeviceHeartbeatMessage(schemaVersion, msgId, occurredAt, source, productKey, deviceCode,
                                          rawStatus == null ? null : HeartbeatStatus.fromCode(rawStatus),
                                          asMap(payload.get(K_METRICS)));
    }

    private static ServiceHeartbeatMessage decodeServiceHeartbeat(int schemaVersion, String msgId,
                                                                  long occurredAt, String source,
                                                                  Map<String, Object> payload) {
        return new ServiceHeartbeatMessage(schemaVersion, msgId, occurredAt, source,
                                           asString(payload.get(K_SERVICE_NAME)),
                                           asString(payload.get(K_INSTANCE_ID)),
                                           asLong(payload.get(K_STARTED_AT), 0L),
                                           asString(payload.get(K_VERSION_TEXT)),
                                           asString(payload.get(K_ADDRESS)),
                                           asMap(payload.get(K_METRICS)));
    }

    private static String pk(Map<String, Object> root) {
        return asString(root.get(K_PRODUCT_KEY));
    }

    private static String dc(Map<String, Object> root) {
        return asString(root.get(K_DEVICE_CODE));
    }

    private static MessageType parseType(String messageTypeHeader) {
        if (messageTypeHeader == null || messageTypeHeader.isBlank()) {
            throw new MessageDecodeException(MessageErrorCode.MESSAGE_TYPE_UNKNOWN, null,
                                             "Kafka header mt 缺失");
        }
        try {
            return MessageType.fromCode(messageTypeHeader);
        } catch (IllegalArgumentException e) {
            throw new MessageDecodeException(MessageErrorCode.MESSAGE_TYPE_UNKNOWN, null,
                                             messageTypeHeader, e);
        }
    }

    private static Map<String, Object> unpackRoot(byte[] value) {
        if (value == null || value.length == 0) {
            throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, null,
                                             "Kafka value 为空");
        }
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(value)) {
            Object root = readValue(unpacker);
            if (!(root instanceof Map)) {
                throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, null,
                                                 "消息根节点必须是 map，实际 " + (root == null ? "nil" : root.getClass().getSimpleName()));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) root;
            return typed;
        } catch (IOException | MessagePackException e) {
            // MessagePackException 是 RuntimeException：畸形字节（如 0xC1 这个 never-used 格式码）
            // 会从这里抛出。不接住它，一条毒消息就会以第三方异常类型炸穿消费循环，
            // 而不是变成一条带 4106 的 DLQ 记录。
            throw new MessageDecodeException(MessageErrorCode.PAYLOAD_DECODE_FAILED, null,
                                             "MessagePack 解码失败", e);
        }
    }

    // ── 通用值转换 ──

    private static void packObject(MessagePacker packer, Object value) throws IOException {
        switch (value) {
            case null -> packer.packNil();
            case Boolean b -> packer.packBoolean(b);
            case Integer i -> packer.packInt(i);
            case Long l -> packer.packLong(l);
            case Short s -> packer.packShort(s);
            case Byte b -> packer.packByte(b);
            case Float f -> packer.packFloat(f);
            case Double d -> packer.packDouble(d);
            case String s -> packer.packString(s);
            case byte[] bytes -> {
                packer.packBinaryHeader(bytes.length);
                packer.writePayload(bytes);
            }
            case Map<?, ?> map -> {
                packer.packMapHeader(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    packObject(packer, String.valueOf(entry.getKey()));
                    packObject(packer, entry.getValue());
                }
            }
            case Collection<?> collection -> {
                packer.packArrayHeader(collection.size());
                for (Object element : collection) {
                    packObject(packer, element);
                }
            }
            default -> throw new IllegalArgumentException(
                "不支持的 payload 值类型: " + value.getClass().getName());
        }
    }

    /**
     * 从 unpacker <b>单遍流式</b>读出 Java 对象，不物化中间的 {@code Value} 树。
     *
     * <p><b>为什么不用 {@code unpacker.unpackValue()} + 递归转换</b>（hotpath-findings.md H18/H20）：
     * 那种写法先由 msgpack 建出一棵不可变 {@code Value} 树，再遍历它建出第二棵 Java 对象树 ——
     * <b>一条消息两份完整对象图</b>。实测 msgpack 解码占 engine 总分配的 <b>55%</b>，
     * 20000 rps 下 young GC 约 1 次/秒、分配速率 ≈327 MB/s。
     * 本实现直接按格式码读取并就地构造，第一棵树整个不再存在。
     *
     * <p><b>类型映射与旧实现逐条一致</b>，调用方无需改动：
     * nil→{@code null}、boolean→{@code Boolean}、integer→<b>{@code Long}</b>、
     * float→<b>{@code Double}</b>、string→{@code String}、binary→{@code byte[]}、
     * array→{@code ArrayList}、map→{@code LinkedHashMap}（key 经 {@code String.valueOf}）。
     *
     * <p><b>唯一的行为变化，且是改进</b>：超出 {@code long} 值域的整数，
     * 旧实现 {@code asIntegerValue().toLong()} 会<b>静默截断</b>成一个错误的值并一路写进 IoTDB；
     * 本实现的 {@link MessageUnpacker#unpackLong()} 抛
     * {@code MessageIntegerOverflowException}（{@code MessagePackException} 子类），
     * 由 {@link #unpackRoot} 既有的 catch 收敛为 {@code PAYLOAD_DECODE_FAILED} 进 DLQ。
     * <b>留证的坏消息优于静默的坏数据</b>，与 §八「确定性拒绝逐条隔离」的取向一致。
     *
     * <p>容器均按已知长度预分配，避免填充过程中扩容重哈希。
     *
     * @throws IOException          底层读取失败
     * @throws MessagePackException 畸形字节、整数溢出等，由调用方转 {@code PAYLOAD_DECODE_FAILED}
     */
    private static Object readValue(MessageUnpacker unpacker) throws IOException {
        ValueType type = unpacker.getNextFormat().getValueType();
        switch (type) {
            case NIL -> {
                unpacker.unpackNil();
                return null;
            }
            case BOOLEAN -> {
                return unpacker.unpackBoolean();
            }
            case INTEGER -> {
                return unpacker.unpackLong();
            }
            case FLOAT -> {
                return unpacker.unpackDouble();
            }
            case STRING -> {
                // 刻意不用 unpacker.unpackString()：msgpack-java 默认的 malformed 处理是 REPLACE，
                // 该路径走 CharsetDecoder + 中间 CharBuffer，实测比直接 new String 分配显著更多。
                // new String(byte[], UTF_8) 对畸形序列同样替换为 U+FFFD，语义一致。
                int len = unpacker.unpackRawStringHeader();
                return len == 0 ? "" : new String(unpacker.readPayload(len), StandardCharsets.UTF_8);
            }
            case BINARY -> {
                return unpacker.readPayload(unpacker.unpackBinaryHeader());
            }
            case ARRAY -> {
                int size = unpacker.unpackArrayHeader();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(readValue(unpacker));
                }
                return list;
            }
            case MAP -> {
                int size = unpacker.unpackMapHeader();
                Map<String, Object> map = LinkedHashMap.newLinkedHashMap(size);
                for (int i = 0; i < size; i++) {
                    // key 只读一次。旧实现曾写成 `toJava(k) == null ? null : String.valueOf(toJava(k))`，
                    // 在同一表达式里调两次 —— 每个 key 被 UTF-8 解码两遍（H18，实测占该热点一半）。
                    Object key = readValue(unpacker);
                    map.put(key == null ? null : String.valueOf(key), readValue(unpacker));
                }
                return map;
            }
            // EXTENSION 等本协议未定义的类型：与旧实现同样整条拒绝，不静默跳过
            default -> throw new IllegalArgumentException("不支持的 MessagePack 类型: " + type);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("期望数值，实际 " + value.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("期望 map，实际 " + value.getClass().getSimpleName());
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, Map<?, ?> value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }
}

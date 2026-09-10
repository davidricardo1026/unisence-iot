package com.unisence.iot.message.codec;

import com.unisence.iot.message.*;
import com.unisence.iot.message.type.HeartbeatStatus;
import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.message.type.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编解码往返与错误码契约，对应 device-message-contract.md §五、§七。
 */
class MessagePackMessageCodecTest {

    private final MessageCodec codec = new MessagePackMessageCodec();

    private static final int VER = MessagePackMessageCodec.SCHEMA_VERSION;
    private static final String MSG_ID = "01J8ZK7Q0000000000000000AB";
    private static final long TS = 1_753_000_000_000L;
    private static final String SRC = "mqtt-driver-01";
    private static final long EPOCH = 7L;
    private static final long SEQ = 11L;
    private static final String PK = "ab12cd";
    private static final String DC = "sensor-001";

    private IotMessage roundTrip(IotMessage message) {
        return codec.decode(message.messageType().code(), codec.encode(message));
    }

    @Test
    @DisplayName("属性消息往返：信封与 values 逐项一致")
    void propertyRoundTrip() {
        DevicePropertyMessage origin = new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC,
                                                                 Map.of("temperature",
                                                                        36.5d,
                                                                        "online",
                                                                        true,
                                                                        "count",
                                                                        7L,
                                                                        "name",
                                                                        "北门"));

        DevicePropertyMessage decoded = assertInstanceOf(DevicePropertyMessage.class, roundTrip(origin));
        assertEquals(origin.schemaVersion(), decoded.schemaVersion());
        assertEquals(origin.msgId(), decoded.msgId());
        assertEquals(origin.occurredAt(), decoded.occurredAt());
        assertEquals(origin.source(), decoded.source());
        assertEquals(origin.productKey(), decoded.productKey());
        assertEquals(origin.deviceCode(), decoded.deviceCode());
        assertEquals(36.5d, decoded.values().get("temperature"));
        assertEquals(true, decoded.values().get("online"));
        assertEquals(7L, decoded.values().get("count"));
        assertEquals("北门", decoded.values().get("name"));
    }

    @Test
    @DisplayName("事件消息往返：嵌套 map 与 list 保持结构")
    void eventRoundTripWithNestedPayload() {
        DeviceEventMessage origin = new DeviceEventMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, "overheat",
                                                           Map.of("detail",
                                                                  Map.of("threshold", 80L),
                                                                  "samples",
                                                                  List.of(81L, 82L)));

        DeviceEventMessage decoded = assertInstanceOf(DeviceEventMessage.class, roundTrip(origin));
        assertEquals("overheat", decoded.identifier());
        assertEquals(Map.of("threshold", 80L), decoded.params().get("detail"));
        assertEquals(List.of(81L, 82L), decoded.params().get("samples"));
    }

    @Test
    @DisplayName("设备创建往返：nodeType 走 tinyint 编码，gatewayCode 保持")
    void deviceCreateRoundTrip() {
        DeviceCreateMessage origin = new DeviceCreateMessage(VER, MSG_ID, TS, SRC, PK, DC, "一号温感",
                                                             NodeType.SUB_DEVICE, "gw-01", Map.of("installedAt", "B2"));

        DeviceCreateMessage decoded = assertInstanceOf(DeviceCreateMessage.class, roundTrip(origin));
        assertEquals(NodeType.SUB_DEVICE, decoded.nodeType());
        assertEquals("gw-01", decoded.gatewayCode());
        assertEquals("一号温感", decoded.deviceName());
        assertEquals("B2", decoded.formData().get("installedAt"));
    }

    @Test
    @DisplayName("心跳往返：status 缺省不写键，解码后仍为 null 而非报错")
    void heartbeatRoundTripWithoutStatus() {
        DeviceHeartbeatMessage origin =
            new DeviceHeartbeatMessage(VER, MSG_ID, TS, SRC, PK, DC, null, Map.of("rssi", -70L));

        DeviceHeartbeatMessage decoded = assertInstanceOf(DeviceHeartbeatMessage.class, roundTrip(origin));
        assertNull(decoded.status());
        assertEquals(HeartbeatStatus.ONLINE, decoded.statusOrDefault());
        assertEquals(-70L, decoded.metrics().get("rssi"));
    }

    @Test
    @DisplayName("服务心跳往返：无 pk/dc，分区键走独立键空间")
    void serviceHeartbeatRoundTrip() {
        ServiceHeartbeatMessage origin = new ServiceHeartbeatMessage(VER,
                                                                     MSG_ID,
                                                                     TS,
                                                                     SRC,
                                                                     "cn-service-engine",
                                                                     "i-0001",
                                                                     1_752_000_000_000L,
                                                                     "1.2.0",
                                                                     "10.0.0.7:9090",
                                                                     Map.of("lagTotal", 3L));

        ServiceHeartbeatMessage decoded =
            assertInstanceOf(ServiceHeartbeatMessage.class, roundTrip(origin));
        assertEquals("cn-service-engine", decoded.serviceName());
        assertEquals("i-0001", decoded.instanceId());
        assertEquals(1_752_000_000_000L, decoded.startedAt());
        assertEquals("10.0.0.7:9090", decoded.address());
        assertEquals("service.cn-service-engine.i-0001", decoded.partitionKey());
    }

    // ---------------- 错误路径 ----------------

    @Test
    @DisplayName("未知 mt 报 4101，且不去解码 value")
    void unknownMessageType() {
        MessageDecodeException e = assertThrows(MessageDecodeException.class,
                                                () -> codec.decode("telemetry", new byte[]{1}));
        assertEquals(MessageErrorCode.MESSAGE_TYPE_UNKNOWN, e.errorCode());

        assertThrows(MessageDecodeException.class, () -> codec.decode(null, new byte[]{1}));
    }

    @Test
    @DisplayName("未知 schema 主版本报 4102，并带出 msgId 供 DLQ 定位")
    void unsupportedSchemaVersion() {
        byte[] encoded = codec.encode(
            new DevicePropertyMessage(99, "m-2", 1L, "src", EPOCH, SEQ, PK, DC, Map.of("t", 1L)));

        MessageDecodeException e = assertThrows(MessageDecodeException.class,
                                                () -> codec.decode(MessageType.PROPERTY.code(), encoded));
        assertEquals(MessageErrorCode.SCHEMA_VERSION_UNSUPPORTED, e.errorCode());
        assertEquals("m-2", e.msgId());
    }

    @Test
    @DisplayName("空 value 报 4106")
    void emptyValueRejected() {
        MessageDecodeException e = assertThrows(MessageDecodeException.class,
                                                () -> codec.decode(MessageType.PROPERTY.code(), new byte[0]));
        assertEquals(MessageErrorCode.PAYLOAD_DECODE_FAILED, e.errorCode());
    }

    @Test
    @DisplayName("损坏字节报 4106 而不是抛出裸 IOException")
    void corruptedBytesRejected() {
        MessageDecodeException e = assertThrows(MessageDecodeException.class,
                                                () -> codec.decode(MessageType.PROPERTY.code(),
                                                                   new byte[]{(byte) 0xC1, 0x02, 0x03}));
        assertEquals(MessageErrorCode.PAYLOAD_DECODE_FAILED, e.errorCode());
    }

    @Test
    @DisplayName("构造器判定的精确错误码透传到解码异常，不降级成 4106")
    void structureErrorCodePreserved() {
        // 合法编码一条属性消息后，把 vals 抹成空 map 重编，模拟发送方违约
        byte[] encoded = codec.encode(new DeviceEventMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, "e", null));
        MessageDecodeException e = assertThrows(MessageDecodeException.class,
                                                () -> codec.decode(MessageType.PROPERTY.code(), encoded));
        // event 的 payload 没有 vals 键 → 空 map → 属性消息的 4107，而非笼统的 4106
        assertEquals(MessageErrorCode.PROPERTY_VALUES_EMPTY, e.errorCode());
        assertEquals(MSG_ID, e.msgId());
    }

    @Test
    @DisplayName("编码不支持的 payload 值类型时明确报错并带出 msgId，不静默丢字段")
    void unsupportedPayloadValueType() {
        DevicePropertyMessage message = new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC,
                                                                  Map.of("when", new java.util.Date()));
        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
        assertTrue(e.getMessage().contains(MSG_ID), e.getMessage());
        assertTrue(e.getMessage().contains("java.util.Date"), e.getMessage());
    }
}

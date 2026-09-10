package com.unisence.iot.message;

import com.unisence.iot.message.codec.MessagePackMessageCodec;
import com.unisence.iot.message.type.HeartbeatStatus;
import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.message.type.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息模型第 1 层（结构性）校验契约，对应 device-message-contract.md §六。
 */
class MessageModelTest {

    private static final int VER = MessagePackMessageCodec.SCHEMA_VERSION;
    private static final String MSG_ID = "01J8ZK7Q0000000000000000AB";
    private static final long TS = 1_753_000_000_000L;
    private static final String SRC = "mqtt-driver-01";
    private static final long EPOCH = 7L;
    private static final long SEQ = 11L;
    private static final String PK = "ab12cd";
    private static final String DC = "sensor-001";

    // ---------------- 信封 ----------------

    @Test
    @DisplayName("msgId 超 36 字符被拒，错误码 4105")
    void msgIdTooLong() {
        MessageStructureException e = assertThrows(MessageStructureException.class,
                                                   () -> new DevicePropertyMessage(VER,
                                                                                   "x".repeat(37),
                                                                                   TS,
                                                                                   SRC,
                                                                                   EPOCH,
                                                                                   SEQ,
                                                                                   PK,
                                                                                   DC,
                                                                                   Map.of("t", 1)));
        assertEquals(MessageErrorCode.ENVELOPE_FIELD_TOO_LONG, e.errorCode());
        assertEquals("msgId", e.field());
    }

    @Test
    @DisplayName("occurredAt 非正数被拒：0 不是合法的发生时间")
    void occurredAtMustBePositive() {
        MessageStructureException e = assertThrows(MessageStructureException.class,
                                                   () -> new DevicePropertyMessage(VER,
                                                                                   MSG_ID,
                                                                                   0L,
                                                                                   SRC,
                                                                                   EPOCH,
                                                                                   SEQ,
                                                                                   PK,
                                                                                   DC,
                                                                                   Map.of("t", 1)));
        assertEquals("occurredAt", e.field());
    }

    @Test
    @DisplayName("信封校验对五类消息一致生效——平铺后每个紧凑构造器都调了 requireEnvelope")
    void envelopeValidationAppliesToEveryMessageType() {
        assertThrows(MessageStructureException.class,
                     () -> new DevicePropertyMessage(VER, null, TS, SRC, EPOCH, SEQ, PK, DC, Map.of("t", 1)));
        assertThrows(MessageStructureException.class,
                     () -> new DeviceEventMessage(VER, null, TS, SRC, EPOCH, SEQ, PK, DC, "e", null));
        assertThrows(MessageStructureException.class,
                     () -> new DeviceCreateMessage(VER, null, TS, SRC, PK, DC, null, NodeType.DIRECT, null, null));
        assertThrows(MessageStructureException.class,
                     () -> new DeviceHeartbeatMessage(VER, null, TS, SRC, PK, DC, null, null));
        assertThrows(MessageStructureException.class,
                     () -> new ServiceHeartbeatMessage(VER, null, TS, SRC, "engine", "i-1", 1L, null, null, null));
    }

    @Test
    @DisplayName("productKey 必须是 6 位小写短码")
    void productKeyIsSixLowerChars() {
        assertThrows(MessageStructureException.class,
                     () -> new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, "ab12c", "d1", Map.of("t", 1)));
        assertThrows(MessageStructureException.class,
                     () -> new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, "AB12CD", "d1", Map.of("t", 1)));
        assertEquals("ab12cd.d1",
                     new DevicePropertyMessage(VER,
                                               MSG_ID,
                                               TS,
                                               SRC,
                                               EPOCH,
                                               SEQ,
                                               "ab12cd",
                                               "d1",
                                               Map.of("t", 1)).partitionKey());
    }

    // ---------------- 类型层次 ----------------

    @Test
    @DisplayName("messageType 由 record 类型固定，不可能与 payload 不一致")
    void messageTypeIsDerivedFromRecordType() {
        assertEquals(MessageType.PROPERTY,
                     new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, Map.of("t", 1)).messageType());
        assertEquals(MessageType.EVENT,
                     new DeviceEventMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, "overheat", null).messageType());
        assertEquals(MessageType.SERVICE_HEARTBEAT,
                     new ServiceHeartbeatMessage(VER,
                                                 MSG_ID,
                                                 TS,
                                                 SRC,
                                                 "engine",
                                                 "i-1",
                                                 1L,
                                                 null,
                                                 null,
                                                 null).messageType());
    }

    @Test
    @DisplayName("设备消息分区键为 productKey.deviceCode，服务心跳走独立键空间")
    void partitionKeys() {
        assertEquals("ab12cd.sensor-001",
                     new DevicePropertyMessage(VER,
                                               MSG_ID,
                                               TS,
                                               SRC,
                                               EPOCH,
                                               SEQ,
                                               PK,
                                               DC,
                                               Map.of("t", 1)).partitionKey());
        assertEquals("service.engine.i-1",
                     new ServiceHeartbeatMessage(VER,
                                                 MSG_ID,
                                                 TS,
                                                 SRC,
                                                 "engine",
                                                 "i-1",
                                                 1L,
                                                 null,
                                                 null,
                                                 null).partitionKey());
    }

    @Test
    @DisplayName("信封字段经 IotMessage 接口可直接读取")
    void envelopeAccessors() {
        IotMessage message = new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, Map.of("t", 1));
        assertEquals(VER, message.schemaVersion());
        assertEquals(MSG_ID, message.msgId());
        assertEquals(TS, message.occurredAt());
        assertEquals(SRC, message.source());
    }

    // ---------------- 各类型专属约束 ----------------

    @Test
    @DisplayName("属性消息空 values 被拒，错误码 4107")
    void propertyValuesNotEmpty() {
        MessageStructureException e = assertThrows(MessageStructureException.class,
                                                   () -> new DevicePropertyMessage(VER,
                                                                                   MSG_ID,
                                                                                   TS,
                                                                                   SRC,
                                                                                   EPOCH,
                                                                                   SEQ,
                                                                                   PK,
                                                                                   DC,
                                                                                   Map.of()));
        assertEquals(MessageErrorCode.PROPERTY_VALUES_EMPTY, e.errorCode());
        assertThrows(MessageStructureException.class,
                     () -> new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, null));
    }

    @Test
    @DisplayName("事件消息缺 identifier 被拒，错误码 4108")
    void eventIdentifierRequired() {
        MessageStructureException e = assertThrows(MessageStructureException.class,
                                                   () -> new DeviceEventMessage(VER,
                                                                                MSG_ID,
                                                                                TS,
                                                                                SRC,
                                                                                EPOCH,
                                                                                SEQ,
                                                                                PK,
                                                                                DC,
                                                                                "  ",
                                                                                null));
        assertEquals(MessageErrorCode.EVENT_IDENTIFIER_MISSING, e.errorCode());
    }

    @Test
    @DisplayName("子设备必须携带 gatewayCode，非子设备携带即非法，错误码 4110")
    void gatewayCodeConsistency() {
        MessageStructureException missing = assertThrows(MessageStructureException.class,
                                                         () -> new DeviceCreateMessage(VER,
                                                                                       MSG_ID,
                                                                                       TS,
                                                                                       SRC,
                                                                                       PK,
                                                                                       DC,
                                                                                       "灯",
                                                                                       NodeType.SUB_DEVICE,
                                                                                       null,
                                                                                       null));
        assertEquals(MessageErrorCode.GATEWAY_CODE_INCONSISTENT, missing.errorCode());

        MessageStructureException unexpected = assertThrows(MessageStructureException.class,
                                                            () -> new DeviceCreateMessage(VER,
                                                                                          MSG_ID,
                                                                                          TS,
                                                                                          SRC,
                                                                                          PK,
                                                                                          DC,
                                                                                          "灯",
                                                                                          NodeType.DIRECT,
                                                                                          "gw-1",
                                                                                          null));
        assertEquals(MessageErrorCode.GATEWAY_CODE_INCONSISTENT, unexpected.errorCode());

        assertEquals("gw-1", new DeviceCreateMessage(VER, MSG_ID, TS, SRC, PK, DC, "灯",
                                                     NodeType.SUB_DEVICE, "gw-1", null).gatewayCode());
    }

    @Test
    @DisplayName("心跳 metrics 白名单外的键被拒，错误码 4109")
    void heartbeatMetricWhitelist() {
        MessageStructureException e = assertThrows(MessageStructureException.class,
                                                   () -> new DeviceHeartbeatMessage(VER,
                                                                                    MSG_ID,
                                                                                    TS,
                                                                                    SRC,
                                                                                    PK,
                                                                                    DC,
                                                                                    null,
                                                                                    Map.of("temperature", 60)));
        assertEquals(MessageErrorCode.HEARTBEAT_METRIC_NOT_ALLOWED, e.errorCode());
        assertEquals("metrics.temperature", e.field());

        assertEquals(1, new DeviceHeartbeatMessage(VER, MSG_ID, TS, SRC, PK, DC, null, Map.of("rssi", -70))
            .metrics().size());
    }

    @Test
    @DisplayName("心跳未上报 status 时按 ONLINE 处理")
    void heartbeatStatusDefault() {
        assertEquals(HeartbeatStatus.ONLINE,
                     new DeviceHeartbeatMessage(VER, MSG_ID, TS, SRC, PK, DC, null, null).statusOrDefault());
        assertEquals(HeartbeatStatus.FAULT,
                     new DeviceHeartbeatMessage(VER,
                                                MSG_ID,
                                                TS,
                                                SRC,
                                                PK,
                                                DC,
                                                HeartbeatStatus.FAULT,
                                                null).statusOrDefault());
    }

    // ---------------- 不可变性 ----------------

    @Test
    @DisplayName("Map 组件做防御性拷贝：构造后改原 map 不影响消息")
    void mapComponentsAreDefensivelyCopied() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("temperature", 20);
        DevicePropertyMessage message = new DevicePropertyMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, mutable);

        mutable.put("temperature", 999);
        assertEquals(20, message.values().get("temperature"));
        assertNotSame(mutable, message.values());
        assertThrows(UnsupportedOperationException.class, () -> message.values().put("x", 1));
    }

    @Test
    @DisplayName("可空 Map 归一为不可变空 map，消费方无需判空")
    void nullMapsBecomeEmpty() {
        assertTrue(new DeviceEventMessage(VER, MSG_ID, TS, SRC, EPOCH, SEQ, PK, DC, "e", null).params().isEmpty());
        assertTrue(new DeviceCreateMessage(VER, MSG_ID, TS, SRC, PK, DC, null, NodeType.DIRECT, null, null)
                       .formData().isEmpty());
    }

    // ---------------- 值域枚举 ----------------

    @Test
    @DisplayName("MessageType 落库编码是小写下划线，不是 name()")
    void messageTypeCodes() {
        assertEquals("device_create", MessageType.DEVICE_CREATE.code());
        assertEquals("service_heartbeat", MessageType.SERVICE_HEARTBEAT.code());
        assertSame(MessageType.EVENT, MessageType.fromCode("event"));
        assertThrows(IllegalArgumentException.class, () -> MessageType.fromCode("unknown"));
    }

    @Test
    @DisplayName("NodeType 编码与 us_iot_device.node_type 的 tinyint 对齐")
    void nodeTypeCodes() {
        assertEquals(1, NodeType.DIRECT.code());
        assertEquals(2, NodeType.GATEWAY.code());
        assertEquals(3, NodeType.SUB_DEVICE.code());
        assertTrue(NodeType.SUB_DEVICE.requiresGatewayCode());
        assertTrue(!NodeType.GATEWAY.requiresGatewayCode());
    }

    @Test
    @DisplayName("错误码全部落在已登记的 4100-4139 区间")
    void errorCodesWithinRegisteredRange() {
        for (MessageErrorCode code : MessageErrorCode.values()) {
            assertTrue(code.code() >= 4100 && code.code() <= 4139,
                       code + " 越界: " + code.code());
        }
    }
}

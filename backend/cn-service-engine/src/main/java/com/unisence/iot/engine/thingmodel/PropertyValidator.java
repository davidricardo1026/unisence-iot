package com.unisence.iot.engine.thingmodel;

import com.unisence.iot.engine.repository.PropertyPoint;
import com.unisence.iot.message.DevicePropertyMessage;
import com.unisence.iot.rule.sdk.PropertyDataType;
import com.unisence.iot.rule.sdk.PropertyDefinition;
import com.unisence.iot.rule.sdk.ThingModelSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 第 3 层物模型校验与标准化（device-message-contract.md §六）。
 *
 * <p>取代此前按 Java 原生类型<b>猜</b> {@code value_type} 的做法 —— 那只保证「结构合法的值不丢类型」，
 * 不保证上报值符合物模型定义：未知属性会照常落盘，`int` 属性上报字符串也不会被发现。
 * 现在类型由 {@code data_type} 决定，列路由也随之确定。
 *
 * <p><b>全或无</b>：任一属性不合法即整条抛出，禁止半条落盘（契约 §六）。
 * 因此本类先完整校验并构建全部行，再由调用方一次性交付，不存在「写了一半才发现不合法」的路径。
 */
public final class PropertyValidator {

    private PropertyValidator() {
    }

    /**
     * 校验并转换为时序数据行。
     *
     * @param snapshot 该产品的物模型快照；{@code null} 表示产品未定义
     * @throws ThingModelViolation 产品未定义 / 未知 identifier / 类型不符，均导致<b>整条</b>消息被拒
     */
    public static ValidatedProperties validateForStorage(DevicePropertyMessage message,
                                                         ThingModelSnapshot snapshot) {
        if (snapshot == null) {
            throw new ThingModelViolation(ThingModelViolation.REASON_PRODUCT_UNKNOWN, null,
                                          "产品未定义物模型: productKey=" + message.productKey());
        }

        List<PropertyPoint> history = new ArrayList<>(message.values().size());
        for (Map.Entry<String, Object> entry : message.values().entrySet()) {
            String identifier = entry.getKey();
            Object value = entry.getValue();

            PropertyDefinition definition = snapshot.property(identifier);
            if (definition == null) {
                throw new ThingModelViolation(ThingModelViolation.REASON_IDENTIFIER_UNKNOWN, identifier,
                                              "物模型未定义该属性: productKey=" + message.productKey() + " identifier=" + identifier);
            }

            PropertyDataType dataType = definition.dataType();
            if (!dataType.accepts(value)) {
                throw new ThingModelViolation(ThingModelViolation.REASON_TYPE_MISMATCH, identifier,
                                              "属性值类型与物模型不符: identifier=" + identifier + " dataType=" + dataType.code()
                                                  + " 实际=" + (value == null ? "null" : value.getClass().getSimpleName()));
            }

            // 声明即存储：物模型里声明的属性一律落时序库，无存储策略开关
            // （latest-property-runtime.md §二）。存储成本由时序库 TTL 承担
            history.add(toPoint(message, identifier, dataType, definition.retentionDays(), value));
        }
        return new ValidatedProperties(List.copyOf(history));
    }

    public record ValidatedProperties(List<PropertyPoint> history) {
    }

    private static PropertyPoint toPoint(DevicePropertyMessage message, String identifier,
                                         PropertyDataType dataType, int retentionDays, Object value) {
        String pk = message.productKey();
        String dc = message.deviceCode();
        long ts = message.occurredAt();
        String msgId = message.msgId();

        return switch (dataType) {
            case BOOL -> PropertyPoint.ofBool(pk, dc, identifier, ts, retentionDays, (Boolean) value, msgId);
            case INT -> PropertyPoint.ofLong(pk,
                                             dc,
                                             identifier,
                                             ts,
                                             retentionDays,
                                             ((Number) value).longValue(),
                                             msgId);
            case FLOAT, DOUBLE -> PropertyPoint.ofDouble(pk, dc, identifier, ts, retentionDays,
                                                         ((Number) value).doubleValue(), msgId);
            case STRING, TEXT, ENUM, IMAGE -> PropertyPoint.ofText(pk, dc, identifier, ts, retentionDays,
                                                                   (String) value, msgId);
        };
    }
}

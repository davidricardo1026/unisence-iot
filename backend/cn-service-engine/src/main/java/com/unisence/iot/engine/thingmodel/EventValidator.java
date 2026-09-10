package com.unisence.iot.engine.thingmodel;

import com.unisence.iot.message.DeviceEventMessage;
import com.unisence.iot.rule.sdk.EventDefinition;
import com.unisence.iot.rule.sdk.EventParamDefinition;
import com.unisence.iot.rule.sdk.PropertyDataType;
import com.unisence.iot.rule.sdk.ThingModelSnapshot;
import com.unisence.iot.timeseries.EventParamValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 事件消息的第 3 层物模型校验（device-message-contract.md §六）。
 *
 * <p><b>事件等级从物模型取，不信报文</b>（thing-model-design.md §十一）：等级决定告警与通知行为，
 * 若允许设备自报，一台固件有问题的设备就能把所有事件标成 error 淹没告警系统。
 */
public final class EventValidator {

    private EventValidator() {
    }

    /**
     * 校验结果：事件等级取自物模型，参数按 {@code input_params} 定义顺序产出强类型值。
     */
    public record ValidatedEvent(String identifier, int eventType, List<EventParamValue> params) {
        public ValidatedEvent {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    /**
     * @throws ThingModelViolation 产品未定义 / 事件未定义 / 参数未定义或类型不符 —— 整条拒绝
     */
    public static ValidatedEvent validate(DeviceEventMessage message, ThingModelSnapshot snapshot) {
        if (snapshot == null) {
            throw new ThingModelViolation(ThingModelViolation.REASON_PRODUCT_UNKNOWN, null,
                                          "产品未定义物模型: productKey=" + message.productKey());
        }
        EventDefinition definition = snapshot.event(message.identifier());
        if (definition == null) {
            throw new ThingModelViolation(ThingModelViolation.REASON_IDENTIFIER_UNKNOWN, message.identifier(),
                                          "物模型未定义该事件: productKey=" + message.productKey()
                                              + " identifier=" + message.identifier());
        }

        for (EventParamDefinition defined : definition.inputParams()) {
            boolean present = message.params().keySet().stream()
                .anyMatch(key -> defined.identifier().equalsIgnoreCase(key));
            if (!present) {
                throw new ThingModelViolation(ThingModelViolation.REASON_IDENTIFIER_UNKNOWN, defined.identifier(),
                                              "事件 " + message.identifier() + " 缺少参数: " + defined.identifier());
            }
        }

        for (Map.Entry<String, Object> entry : message.params().entrySet()) {
            String name = entry.getKey();
            EventParamDefinition param = definition.param(name);
            if (param == null) {
                throw new ThingModelViolation(ThingModelViolation.REASON_IDENTIFIER_UNKNOWN, name,
                                              "事件 " + message.identifier() + " 未定义参数: " + name);
            }
            PropertyDataType dataType = param.dataType();
            if (!dataType.accepts(entry.getValue())) {
                throw new ThingModelViolation(ThingModelViolation.REASON_TYPE_MISMATCH, name,
                                              "事件参数类型与物模型不符: event=" + message.identifier() + " param=" + name
                                                  + " dataType=" + dataType.code());
            }
        }

        List<EventParamValue> params = new ArrayList<>(definition.inputParams().size());
        for (EventParamDefinition defined : definition.inputParams()) {
            params.add(new EventParamValue(defined.identifier().toLowerCase(Locale.ROOT),
                                           defined.dataType(),
                                           lookup(message.params(), defined.identifier())));
        }
        String identifier = definition.canonicalIdentifier() != null
            ? definition.canonicalIdentifier()
            : message.identifier().toLowerCase(Locale.ROOT);
        return new ValidatedEvent(identifier, definition.eventType().code(), params);
    }

    private static Object lookup(Map<String, Object> params, String identifier) {
        Object exact = params.get(identifier);
        if (exact != null || params.containsKey(identifier)) {
            return exact;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (identifier.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}

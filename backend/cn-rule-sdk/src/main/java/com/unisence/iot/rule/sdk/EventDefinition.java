package com.unisence.iot.rule.sdk;

import java.util.List;
import java.util.Locale;

/**
 * 物模型事件定义只读快照，对齐 {@code us_iot_tm_event}。
 *
 * <p>事件只有 {@code inputParams}，没有输出参数。参数 identifier 大小写不敏感。
 */
public record EventDefinition(
    String identifier,
    String eventName,
    EventLevel eventType,
    List<EventParamDefinition> inputParams,
    boolean ttlEnabled,
    Integer ttlValue,
    String ttlUnit) {

    public EventDefinition {
        inputParams = inputParams == null ? List.of() : List.copyOf(inputParams);
        EventDataRetention.requireValid(ttlEnabled, ttlValue, ttlUnit);
    }

    /**
     * 参数未定义时返回 null。按大小写不敏感匹配。
     */
    public EventParamDefinition param(String paramIdentifier) {
        if (paramIdentifier == null) {
            return null;
        }
        for (EventParamDefinition param : inputParams) {
            if (param.identifier().equalsIgnoreCase(paramIdentifier)) {
                return param;
            }
        }
        return null;
    }

    public String canonicalIdentifier() {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }
}

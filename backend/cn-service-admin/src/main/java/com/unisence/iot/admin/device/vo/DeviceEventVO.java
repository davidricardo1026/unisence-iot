package com.unisence.iot.admin.device.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DeviceEventVO(
    long occurredAt,
    String identifier,
    String eventName,
    int eventType,
    Map<String, Object> params,
    String msgId) {

    public DeviceEventVO {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}

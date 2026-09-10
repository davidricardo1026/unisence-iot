package com.unisence.iot.timeseries;

import java.util.*;

public record DeviceEventRow(
    String productKey,
    String deviceCode,
    String identifier,
    int eventType,
    String msgId,
    long occurredAt,
    Map<String, Object> params) {

    public DeviceEventRow {
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(deviceCode, "deviceCode");
        EventTableNames.requireIdentifier(identifier);
        identifier = identifier.toLowerCase(Locale.ROOT);
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}

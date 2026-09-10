package com.unisence.iot.timeseries;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record DeviceEventWrite(
    String productKey,
    String deviceCode,
    String identifier,
    int eventType,
    String msgId,
    long occurredAt,
    List<EventParamValue> params) {

    public DeviceEventWrite {
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(deviceCode, "deviceCode");
        EventTableNames.requireIdentifier(identifier);
        identifier = identifier.toLowerCase(Locale.ROOT);
        params = params == null ? List.of() : List.copyOf(params);
    }
}

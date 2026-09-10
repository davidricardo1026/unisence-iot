package com.unisence.iot.timeseries;

public record PropertyLog(
    String productKey, String deviceCode, String identifier, int valueType,
    int retentionDays, Object value, String msgId, long occurredAt) {
}

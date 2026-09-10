package com.unisence.iot.timeseries;

public record PropertyValue(String identifier, Object value, int valueType, long occurredAt, String msgId) {
}

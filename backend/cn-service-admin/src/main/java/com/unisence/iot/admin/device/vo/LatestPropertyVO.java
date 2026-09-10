package com.unisence.iot.admin.device.vo;

public record LatestPropertyVO(
    String identifier,
    Object value,
    int valueType,
    long occurredAt,
    String msgId) {
}

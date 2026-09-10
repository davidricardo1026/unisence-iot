package com.unisence.iot.admin.device.vo;

public record PropertyRawValueVO(
    long occurredAt,
    Object value,
    int valueType,
    String msgId) {
}

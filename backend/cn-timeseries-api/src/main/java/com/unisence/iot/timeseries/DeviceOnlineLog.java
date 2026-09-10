package com.unisence.iot.timeseries;

public record DeviceOnlineLog(
    String productKey, String deviceCode, int event, String reason, long occurredAt) {
}

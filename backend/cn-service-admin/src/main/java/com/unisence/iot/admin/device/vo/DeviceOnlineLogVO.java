package com.unisence.iot.admin.device.vo;

/**
 * 设备上下线跳变记录。
 *
 * @param occurredAt 跳变发生时间（epoch millis）
 * @param event      1-上线，2-离线
 * @param reason     connect / heartbeat_timeout / lwt / disconnect
 */
public record DeviceOnlineLogVO(long occurredAt, int event, String reason) {

    public DeviceOnlineLogVO {
        if (event != 1 && event != 2) {
            throw new IllegalArgumentException("未知上下线事件: " + event);
        }
        reason = reason == null ? "" : reason;
    }
}

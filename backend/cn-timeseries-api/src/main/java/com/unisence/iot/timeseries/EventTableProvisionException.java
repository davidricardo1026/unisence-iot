package com.unisence.iot.timeseries;

/**
 * 事件表 CREATE / ADD / DROP 失败。Admin 映射为错误码 3025（HTTP 5xx）。
 */
public class EventTableProvisionException extends RuntimeException {

    public EventTableProvisionException(String message, Throwable cause) {
        super(message, cause);
    }
}

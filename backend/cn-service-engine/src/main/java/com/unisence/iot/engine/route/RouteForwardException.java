package com.unisence.iot.engine.route;

/**
 * 透传转发失败（发送异常或 ack 超时）。
 *
 * <p>属于基础设施故障而非毒消息：调用方必须让它从 {@code handleBatch} 冒泡，触发整批回退重放，
 * 禁止捕获后写 DLQ 或继续提交 offset。
 */
public final class RouteForwardException extends RuntimeException {

    private final String topic;
    private final String msgId;
    private final long ruleId;

    public RouteForwardException(String topic, String msgId, long ruleId, Throwable cause) {
        super("透传转发失败: topic=" + topic + " msgId=" + msgId + " ruleId=" + ruleId, cause);
        this.topic = topic;
        this.msgId = msgId;
        this.ruleId = ruleId;
    }

    public String topic() {
        return topic;
    }

    public String msgId() {
        return msgId;
    }

    public long ruleId() {
        return ruleId;
    }
}

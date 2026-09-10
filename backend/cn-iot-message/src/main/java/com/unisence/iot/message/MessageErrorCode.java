package com.unisence.iot.message;

/**
 * 上行消息错误码，占用 {@code 4100-4139}，落在 api-standard.md 已登记的
 * {@code 4000-4999}「驱动/协议/采集/Kafka 发布」区间内。
 *
 * <p>分工：除 {@code 4111} 外均为结构性错误，由消息 record 的紧凑构造器或 codec
 * 直接判定；{@code 4111} 为接入层时钟策略错误（契约 §六 第 2 层），本模块只定义码值，
 * 因为时钟偏移窗口是运行配置，零依赖模块看不到。
 */
public enum MessageErrorCode {

    MESSAGE_TYPE_UNKNOWN(4101, "未知的消息类型"),
    SCHEMA_VERSION_UNSUPPORTED(4102, "不支持的 schema 主版本"),
    MESSAGE_TOO_LARGE(4103, "消息超出大小上限"),
    ENVELOPE_FIELD_MISSING(4104, "必填信封字段缺失"),
    ENVELOPE_FIELD_TOO_LONG(4105, "信封字段超长"),
    PAYLOAD_DECODE_FAILED(4106, "payload 结构不符"),
    PROPERTY_VALUES_EMPTY(4107, "属性消息的 values 为空"),
    EVENT_IDENTIFIER_MISSING(4108, "事件消息缺少 identifier"),
    HEARTBEAT_METRIC_NOT_ALLOWED(4109, "心跳 metrics 含白名单外的键"),
    GATEWAY_CODE_INCONSISTENT(4110, "gatewayCode 与 nodeType 不一致"),
    OCCURRED_AT_OUT_OF_RANGE(4111, "occurredAt 超出允许的时钟偏移窗口"),
    DELIVERY_SEQUENCE_INVALID(4112, "交付纪元或序号不合法");

    private final int code;
    private final String message;

    MessageErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}

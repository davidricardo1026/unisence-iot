package com.unisence.iot.message.type;

/**
 * 五类上行消息。既是 Kafka record header {@code mt} 的取值域，也是两张规则表 {@code message_type} 列的取值域。
 *
 * <p>落库与线上编码是 {@link #code()} 的小写下划线形式，不是 {@link #name()}。
 * DDL 已按 ddl-conventions.md §4 移除 CHECK 约束，本枚举是该列取值域的唯一真相源
 * —— 全仓库只允许存在这一份定义。
 *
 * <p>本枚举刻意不提供 {@code requiresDeviceCode()} / {@code hasIdentifier()} 这类谓词：
 * 消息模型已按 {@code DeviceMessage} / {@code ServiceHeartbeatMessage} 分层，
 * 「有没有 deviceCode」「有没有 identifier」是编译期的类型差异，不该退化成运行期判断。
 */
public enum MessageType {

    DEVICE_CREATE("device_create"),
    PROPERTY("property"),
    EVENT("event"),
    DEVICE_HEARTBEAT("device_heartbeat"),
    SERVICE_HEARTBEAT("service_heartbeat");

    private final String code;

    MessageType(String code) {
        this.code = code;
    }

    /**
     * 落库与 Kafka header {@code mt} 使用的编码。
     */
    public String code() {
        return code;
    }

    public static MessageType fromCode(String code) {
        for (MessageType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的消息类型编码: " + code);
    }
}

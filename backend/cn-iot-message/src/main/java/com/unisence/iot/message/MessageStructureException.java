package com.unisence.iot.message;

/**
 * 消息结构性校验失败，由各 record 的紧凑构造器抛出（契约 §六 第 1 层）。
 *
 * <p>继承 {@link IllegalArgumentException}，使 record 构造器保持 Java 惯用语义；
 * 同时携带 {@link MessageErrorCode} 与出错字段，让 codec 能把精确错误码透传到 DLQ 记录，
 * 而不是只剩一句人类可读的 message。
 *
 * <p>刻意不继承 {@code cn-common} 的 {@code BusinessException}：本模块被 engine 与驱动共享，
 * 二者都没有北向 HTTP 语义，不该为了一个异常基类把整个 Spring 拖进来。
 */
public class MessageStructureException extends IllegalArgumentException {

    private final transient MessageErrorCode errorCode;
    private final transient String field;

    public MessageStructureException(MessageErrorCode errorCode, String field, String detail) {
        super(errorCode.message() + " [" + field + "]: " + detail);
        this.errorCode = errorCode;
        this.field = field;
    }

    public MessageErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 出错字段名，供 DLQ 记录与排查定位。
     */
    public String field() {
        return field;
    }
}

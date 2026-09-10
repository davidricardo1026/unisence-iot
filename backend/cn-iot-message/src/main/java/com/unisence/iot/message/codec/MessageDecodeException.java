package com.unisence.iot.message.codec;

import com.unisence.iot.message.MessageErrorCode;

/**
 * 解码失败。携带错误码与（若已解出的）msgId，供 DLQ 记录定位到具体消息。
 *
 * <p>msgId 可能为 null —— 信封本身损坏时根本读不出它，此时只能靠 Kafka 的
 * topic/partition/offset 定位，DLQ 记录必须同时写入这三项。
 */
public class MessageDecodeException extends RuntimeException {

    private final transient MessageErrorCode errorCode;
    private final transient String msgId;

    public MessageDecodeException(MessageErrorCode errorCode, String msgId, String detail) {
        this(errorCode, msgId, detail, null);
    }

    public MessageDecodeException(MessageErrorCode errorCode, String msgId,
                                  String detail, Throwable cause) {
        super("[" + errorCode.code() + "] " + errorCode.message()
                  + (msgId == null ? "" : " msgId=" + msgId) + ": " + detail, cause);
        this.errorCode = errorCode;
        this.msgId = msgId;
    }

    public MessageErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 可为 null：信封损坏时 msgId 尚未解出。
     */
    public String msgId() {
        return msgId;
    }
}

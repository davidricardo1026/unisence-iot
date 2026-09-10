package com.unisence.iot.message;

/**
 * 信封字段的共享结构性校验（device-message-contract.md §六 第 1 层）。
 *
 * <p>基础信封字段平铺在五个消息 record 上，属性/事件的交付字段同样平铺；
 * 不再封装成 {@code MessageHeader}，
 * 因此校验无法靠单一紧凑构造器兜住 —— <b>每个 record 的紧凑构造器都必须显式调用
 * {@link #requireEnvelope}</b>。新增第六类消息时漏调不会有编译错误，
 * 这是平铺换来的已知代价，靠 §十 Audit 清单逐项核对。
 */
final class MessageFields {

    private MessageFields() {
    }

    /**
     * 四个信封字段的统一校验入口。
     */
    static void requireEnvelope(int schemaVersion, String msgId, long occurredAt, String source) {
        if (schemaVersion < 1) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                "schemaVersion", "必须为正整数，实际 " + schemaVersion);
        }
        requireText(msgId, IotMessage.MAX_MSG_ID_CHARS, "msgId");
        if (occurredAt <= 0) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                "occurredAt", "必须为正 epoch millis，实际 " + occurredAt);
        }
        requireText(source, IotMessage.MAX_SOURCE_CHARS, "source");
    }

    /**
     * 设备标识两字段的统一校验入口。四个 {@link DeviceMessage} record 的紧凑构造器都必须调它。
     *
     * <p>{@code productKey} 强制定长 + 小写字母数字 —— 定长是「设备键空间与 {@code service.} 键空间
     * 不可能碰撞」这一不变量的前提（见 {@link DeviceMessage#partitionKey()}）。
     */
    static void requireDevice(String productKey, String deviceCode) {
        if (productKey == null || productKey.length() != DeviceMessage.PRODUCT_KEY_CHARS) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                "productKey",
                                                "必须为 " + DeviceMessage.PRODUCT_KEY_CHARS + " 位短码，实际 " + productKey);
        }
        if (!isLowerAlphanumeric(productKey)) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                "productKey", "只允许小写字母与数字，实际 " + productKey);
        }
        requireText(deviceCode, DeviceMessage.MAX_DEVICE_CODE_CHARS, "deviceCode");
    }

    static void requireDelivery(long deliveryEpoch, long deliverySequence) {
        if (deliveryEpoch <= 0) {
            throw new MessageStructureException(MessageErrorCode.DELIVERY_SEQUENCE_INVALID,
                                                "deliveryEpoch", "必须为正整数，实际 " + deliveryEpoch);
        }
        if (deliverySequence < 0) {
            throw new MessageStructureException(MessageErrorCode.DELIVERY_SEQUENCE_INVALID,
                                                "deliverySequence", "不得为负数，实际 " + deliverySequence);
        }
    }

    private static boolean isLowerAlphanumeric(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 非空 + 长度上限，是全模块字符串字段的统一入口。
     */
    static void requireText(String value, int maxChars, String field) {
        if (value == null || value.isBlank()) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_MISSING,
                                                field, "不可为空");
        }
        if (value.length() > maxChars) {
            throw new MessageStructureException(MessageErrorCode.ENVELOPE_FIELD_TOO_LONG,
                                                field, value.length() + " > " + maxChars);
        }
    }
}

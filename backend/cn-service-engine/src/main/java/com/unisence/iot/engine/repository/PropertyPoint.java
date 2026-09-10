package com.unisence.iot.engine.repository;

/**
 * 属性历史固定类型×保留档位表的一行。
 *
 * <p>{@code valueType + retentionDays} 决定物理表，{@code identifier} 作 TAG，每行只有一个 {@code value} FIELD。
 *
 * @param timestampMs 时序表的 {@code time} 列 —— 取报文 {@code occurredAt}（采样时刻），不是接收时刻
 * @param valueType   1-bool / 2-long / 3-double / 4-text
 */
public record PropertyPoint(
    String productKey,
    String deviceCode,
    String identifier,
    long timestampMs,
    int valueType,
    int retentionDays,
    Boolean valueBool,
    Long valueLong,
    Double valueDouble,
    String valueText,
    String msgId) {

    public static final int TYPE_BOOL = 1;
    public static final int TYPE_LONG = 2;
    public static final int TYPE_DOUBLE = 3;
    public static final int TYPE_TEXT = 4;

    public static PropertyPoint ofBool(String productKey, String deviceCode, String identifier,
                                       long timestampMs, int retentionDays, boolean value, String msgId) {
        return new PropertyPoint(productKey, deviceCode, identifier, timestampMs,
                                 TYPE_BOOL, retentionDays, value, null, null, null, msgId);
    }

    public static PropertyPoint ofLong(String productKey, String deviceCode, String identifier,
                                       long timestampMs, int retentionDays, long value, String msgId) {
        return new PropertyPoint(productKey, deviceCode, identifier, timestampMs,
                                 TYPE_LONG, retentionDays, null, value, null, null, msgId);
    }

    public static PropertyPoint ofDouble(String productKey, String deviceCode, String identifier,
                                         long timestampMs, int retentionDays, double value, String msgId) {
        return new PropertyPoint(productKey, deviceCode, identifier, timestampMs,
                                 TYPE_DOUBLE, retentionDays, null, null, value, null, msgId);
    }

    public static PropertyPoint ofText(String productKey, String deviceCode, String identifier,
                                       long timestampMs, int retentionDays, String value, String msgId) {
        return new PropertyPoint(productKey, deviceCode, identifier, timestampMs,
                                 TYPE_TEXT, retentionDays, null, null, null, value, msgId);
    }
}

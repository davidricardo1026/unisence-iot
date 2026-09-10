package com.unisence.iot.rule.sdk;

/**
 * 事件明细表 TTL 取值域。事件独占表可继承数据库默认 TTL，或使用正整数小时/天覆盖保留期。
 */
public final class EventDataRetention {

    public static final int DEFAULT_VALUE = 7;
    public static final String DEFAULT_UNIT = "d";

    private EventDataRetention() {
    }

    public static void requireValid(boolean enabled, Integer value, String unit) {
        if (!enabled) {
            if (value != null || unit != null) {
                throw new IllegalArgumentException("使用数据库默认 TTL 时数值和单位必须为空");
            }
            return;
        }
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("事件 TTL 数值必须为正整数");
        }
        normalizeUnit(unit);
    }

    public static String greptimeTtl(int value, String unit) {
        requireValid(true, value, unit);
        return value + normalizeUnit(unit);
    }

    public static String normalizeUnit(String unit) {
        if (unit == null) {
            throw new IllegalArgumentException("事件 TTL 单位不能为空");
        }
        if (!unit.equals("h") && !unit.equals("d")) {
            throw new IllegalArgumentException("事件 TTL 单位只允许 h 或 d");
        }
        return unit;
    }
}

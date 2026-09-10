package com.unisence.iot.timeseries;

import com.unisence.iot.rule.sdk.PropertyDataType;

import java.util.Locale;

/**
 * 事件参数 {@link PropertyDataType} → Greptime SQL 类型。禁止另发明一套 dataType 枚举。
 */
public final class EventColumnTypes {

    private EventColumnTypes() {
    }

    public static String greptimeSqlType(PropertyDataType type) {
        if (type == null) {
            throw new IllegalArgumentException("dataType 不可为空");
        }
        return switch (type.valueType()) {
            case PropertyDataType.VALUE_TYPE_BOOL -> "BOOLEAN";
            case PropertyDataType.VALUE_TYPE_LONG -> "INT64";
            case PropertyDataType.VALUE_TYPE_DOUBLE -> "FLOAT64";
            case PropertyDataType.VALUE_TYPE_TEXT -> "STRING";
            default -> throw new IllegalArgumentException("未知的 valueType: " + type.valueType());
        };
    }

    /**
     * 把供应商 {@code TYPE_NAME} / {@code data_type} 收成与 {@link #greptimeSqlType} 可比较的形式。
     */
    public static String normalizeSqlType(String vendorType) {
        if (vendorType == null || vendorType.isBlank()) {
            return "";
        }
        String raw = vendorType.trim();
        int paren = raw.indexOf('(');
        if (paren > 0) {
            raw = raw.substring(0, paren);
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "INT64", "BIGINT", "LONG", "INT8" -> "INT64";
            case "FLOAT64", "DOUBLE", "FLOAT", "FLOAT8" -> "FLOAT64";
            case "STRING", "VARCHAR", "TEXT", "CHAR" -> "STRING";
            case "INT32", "INT", "INTEGER" -> "INT32";
            default -> upper;
        };
    }
}

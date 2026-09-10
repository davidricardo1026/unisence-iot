package com.unisence.iot.timeseries;

import com.unisence.iot.rule.sdk.DataRetention;
import com.unisence.iot.rule.sdk.PropertyDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 属性历史物理表名的唯一生成点：四种物理值类型乘三个固定保留档位。
 */
public final class PropertyTableNames {

    private PropertyTableNames() {
    }

    public static String physicalTable(int valueType, int retentionDays) {
        DataRetention.requireAllowed(retentionDays);
        String type = switch (valueType) {
            case PropertyDataType.VALUE_TYPE_BOOL -> "bool";
            case PropertyDataType.VALUE_TYPE_LONG -> "long";
            case PropertyDataType.VALUE_TYPE_DOUBLE -> "double";
            case PropertyDataType.VALUE_TYPE_TEXT -> "text";
            default -> throw new IllegalArgumentException("未知属性 valueType: " + valueType);
        };
        return "device_property_" + type + "_" + retentionDays + "d";
    }

    public static List<Table> all() {
        List<Table> tables = new ArrayList<>(12);
        for (int valueType = PropertyDataType.VALUE_TYPE_BOOL;
             valueType <= PropertyDataType.VALUE_TYPE_TEXT;
             valueType++) {
            for (int retentionDays : DataRetention.ALLOWED_DAYS) {
                tables.add(new Table(physicalTable(valueType, retentionDays), valueType, retentionDays));
            }
        }
        return List.copyOf(tables);
    }

    public record Table(String name, int valueType, int retentionDays) {
    }
}

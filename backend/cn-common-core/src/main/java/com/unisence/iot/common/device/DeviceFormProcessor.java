package com.unisence.iot.common.device;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * 手工建档与上行自动建档共享的动态表单写入语义。
 */
public final class DeviceFormProcessor {

    private static final Set<String> TYPES =
        Set.of("string", "int", "float", "bool", "enum", "password", "text");

    private DeviceFormProcessor() {
    }

    public static PreparedForm prepareCreate(Map<String, Object> schema,
                                             Map<String, Object> incoming,
                                             UnaryOperator<String> encrypt) {
        Map<String, Object> values = incoming == null ? Map.of() : incoming;
        Map<String, Object> stored = new LinkedHashMap<>();
        List<IndexValue> indexes = new ArrayList<>();
        for (Field field : fields(schema)) {
            boolean present = values.containsKey(field.key());
            Object raw = values.get(field.key());
            if (!present || raw == null || String.valueOf(raw).isBlank()) {
                if (field.required()) {
                    throw new DeviceFormException("必填字段缺失: " + field.key());
                }
                continue;
            }
            Object value = field.sensitive() ? encrypt.apply(String.valueOf(raw)) : raw;
            stored.put(field.key(), value);
            if (field.searchable()) {
                indexes.add(toIndex(field, raw));
            }
        }
        return new PreparedForm(Map.copyOf(stored), List.copyOf(indexes));
    }

    @SuppressWarnings("unchecked")
    private static List<Field> fields(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        if (!(schema.get("groups") instanceof List<?> groups)) {
            throw new DeviceFormException("device_form_schema.groups 必须为数组");
        }
        List<Field> fields = new ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (Object groupValue : groups) {
            if (!(groupValue instanceof Map<?, ?> group)
                || !(group.get("fields") instanceof List<?> groupFields)) {
                throw new DeviceFormException("device_form_schema group/fields 格式非法");
            }
            for (Object fieldValue : groupFields) {
                if (!(fieldValue instanceof Map<?, ?> rawField)) {
                    throw new DeviceFormException("device_form_schema field 必须为对象");
                }
                Map<String, Object> field = (Map<String, Object>) rawField;
                String key = text(field.get("key"));
                String type = text(field.get("type"));
                if (key == null || key.isBlank() || !keys.add(key) || !TYPES.contains(type)) {
                    throw new DeviceFormException("device_form_schema 字段 key/type 非法: " + key);
                }
                boolean sensitive = bool(field.get("sensitive"));
                boolean searchable = bool(field.get("searchable"));
                if (sensitive && searchable || "text".equals(type) && searchable) {
                    throw new DeviceFormException("敏感字段或 text 字段不可搜索: " + key);
                }
                fields.add(new Field(key, type, bool(field.get("required")), sensitive, searchable));
            }
        }
        return fields;
    }

    private static IndexValue toIndex(Field field, Object raw) {
        return switch (field.type()) {
            case "int", "float" -> IndexValue.decimal(field.key(), decimal(raw, field));
            case "bool" -> IndexValue.bool(field.key(), boolValue(raw, field.key()));
            default -> {
                String value = String.valueOf(raw);
                if (value.length() > 255) {
                    throw new DeviceFormException("可搜字段值超过 255: " + field.key());
                }
                yield IndexValue.text(field.key(), value);
            }
        };
    }

    private static BigDecimal decimal(Object raw, Field field) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw)).stripTrailingZeros();
            if ("int".equals(field.type()) && value.scale() > 0
                || value.scale() > 10 || value.precision() - value.scale() > 20) {
                throw new DeviceFormException("数值字段格式或精度非法: " + field.key());
            }
            return value;
        } catch (NumberFormatException error) {
            throw new DeviceFormException("数值字段格式非法: " + field.key());
        }
    }

    private static Boolean boolValue(Object raw, String key) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if ("true".equalsIgnoreCase(String.valueOf(raw))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(raw))) return false;
        throw new DeviceFormException("布尔字段格式非法: " + key);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record Field(String key, String type, boolean required, boolean sensitive, boolean searchable) {
    }

    public record PreparedForm(Map<String, Object> stored, List<IndexValue> indexes) {
    }

    public record IndexValue(String fieldKey, String valueText, BigDecimal valueDecimal, Boolean valueBoolean) {
        private static IndexValue text(String key, String value) {
            return new IndexValue(key, value, null, null);
        }

        private static IndexValue decimal(String key, BigDecimal value) {
            return new IndexValue(key, null, value, null);
        }

        private static IndexValue bool(String key, Boolean value) {
            return new IndexValue(key, null, null, value);
        }
    }

    public static final class DeviceFormException extends IllegalArgumentException {
        public DeviceFormException(String message) {
            super(message);
        }
    }
}

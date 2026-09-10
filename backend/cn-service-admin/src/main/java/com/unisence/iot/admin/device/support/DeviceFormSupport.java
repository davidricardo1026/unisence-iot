package com.unisence.iot.admin.device.support;

import com.unisence.iot.admin.device.dto.DeviceFormFilter;
import com.unisence.iot.common.crypto.FieldEncryptor;
import com.unisence.iot.common.device.DeviceFormProcessor;
import com.unisence.iot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

@Component
@Slf4j
public class DeviceFormSupport {

    private static final String PLACEHOLDER = "******";
    private static final Set<String> FIELD_TYPES = Set.of("string", "int", "float", "bool", "enum", "password", "text");

    private final FieldEncryptor fieldEncryptor;
    private final JsonMaps jsonMaps;

    public DeviceFormSupport(FieldEncryptor fieldEncryptor, JsonMaps jsonMaps) {
        this.fieldEncryptor = fieldEncryptor;
        this.jsonMaps = jsonMaps;
    }

    @SuppressWarnings("unchecked")
    public void validateSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        Object groupsObj = schema.get("groups");
        if (!(groupsObj instanceof List<?> groups)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "device_form_schema.groups 必须为数组");
        }
        Map<String, Boolean> groupKeys = new HashMap<>();
        Map<String, Boolean> fieldKeys = new HashMap<>();
        for (Object g : groups) {
            if (!(g instanceof Map<?, ?> gm)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "group 必须为对象");
            }
            String groupKey = str(gm.get("key"));
            String groupLabel = str(gm.get("label"));
            if (!StringUtils.hasText(groupKey) || !StringUtils.hasText(groupLabel)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "group.key 和 group.label 必填");
            }
            if (groupKeys.put(groupKey, Boolean.TRUE) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "group.key 重复: " + groupKey);
            }
            Object fieldsObj = gm.get("fields");
            if (!(fieldsObj instanceof List<?> fields)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "group.fields 必须为数组: " + groupKey);
            }
            if (fields.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "group.fields 至少包含一个字段: " + groupKey);
            }
            for (Object f : fields) {
                if (!(f instanceof Map<?, ?> fm)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "field 必须为对象");
                }
                Map<String, Object> field = (Map<String, Object>) fm;
                String key = str(field.get("key"));
                if (!StringUtils.hasText(key)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "field.key 必填");
                }
                if (!StringUtils.hasText(str(field.get("label")))) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "field.label 必填: " + key);
                }
                if (fieldKeys.put(key, Boolean.TRUE) != null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "field.key 重复: " + key);
                }
                boolean sensitive = bool(field.get("sensitive"));
                boolean searchable = bool(field.get("searchable"));
                if (sensitive && searchable) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "敏感字段不可 searchable: " + key);
                }
                String type = str(field.get("type"));
                if (!FIELD_TYPES.contains(type)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "不支持的 field.type: " + key);
                }
                if ("enum".equals(type) && (!(field.get("options") instanceof List<?> options) || options.isEmpty())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "枚举字段至少需要一个 options: " + key);
                }
                String mask = str(field.get("mask"));
                if ("password".equals(type) && mask != null && !"all".equals(mask)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "password 类型 mask 必须为 all: " + key);
                }
                if ("custom".equals(mask)) {
                    Object rule = field.get("maskRule");
                    if (!(rule instanceof Map<?, ?> rm) || rm.get("keepHead") == null || rm.get("keepTail") == null) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST,
                                                    3007,
                                                    "custom mask 须含 maskRule.keepHead/keepTail: " + key);
                    }
                } else if (field.containsKey("maskRule") && field.get("maskRule") != null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "非 custom 禁止 maskRule: " + key);
                }
            }
        }
    }

    public List<FieldMeta> parseFields(String schemaJson) {
        Map<String, Object> schema = jsonMaps.readMap(schemaJson);
        List<FieldMeta> list = new ArrayList<>();
        Object groupsObj = schema.get("groups");
        if (!(groupsObj instanceof List<?> groups)) {
            return list;
        }
        for (Object g : groups) {
            if (!(g instanceof Map<?, ?> gm)) {
                continue;
            }
            Object fieldsObj = gm.get("fields");
            if (!(fieldsObj instanceof List<?> fields)) {
                continue;
            }
            for (Object f : fields) {
                if (!(f instanceof Map<?, ?> fm)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> field = (Map<String, Object>) fm;
                FieldMeta meta = new FieldMeta();
                meta.key = str(field.get("key"));
                meta.type = str(field.get("type"));
                meta.required = bool(field.get("required"));
                meta.searchable = bool(field.get("searchable"));
                meta.sensitive = bool(field.get("sensitive"));
                meta.listVisible = bool(field.get("listVisible"));
                meta.mask = str(field.get("mask"));
                if (field.get("maskRule") instanceof Map<?, ?> rm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rule = (Map<String, Object>) rm;
                    meta.maskRule = rule;
                }
                if (StringUtils.hasText(meta.key)) {
                    list.add(meta);
                }
            }
        }
        return list;
    }

    public Set<String> fieldKeys(String schemaJson) {
        return parseFields(schemaJson).stream().map(field -> field.key).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Schema JSON 没有字段 ID，不能直接区分“改 key”与“删除后新增”。
     * 因此要求两步保存：一次只能删除旧 key 或新增 key，不能两者同时发生。
     */
    public void assertKeyEvolution(String previousSchemaJson, Map<String, Object> nextSchema) {
        Set<String> previous = fieldKeys(previousSchemaJson);
        Set<String> next = new HashSet<>();
        if (nextSchema != null) {
            for (FieldMeta field : parseFields(jsonMaps.write(nextSchema))) {
                next.add(field.key);
            }
        }
        boolean removed = previous.stream().anyMatch(key -> !next.contains(key));
        boolean added = next.stream().anyMatch(key -> !previous.contains(key));
        if (removed && added) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        3007,
                                        "已保存字段 key 不可修改；请先保存删除，再新增字段");
        }
    }

    /**
     * 合并写入：敏感字段若传占位或不传则保留旧密文；新明文加密。
     */
    public Map<String, Object> mergeAndEncrypt(String schemaJson,
                                               Map<String, Object> incoming,
                                               Map<String, Object> existingStored) {
        if (existingStored == null || existingStored.isEmpty()) {
            try {
                return DeviceFormProcessor.prepareCreate(
                    jsonMaps.readMap(schemaJson), incoming, fieldEncryptor::encrypt).stored();
            } catch (DeviceFormProcessor.DeviceFormException error) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3008, error.getMessage());
            }
        }
        List<FieldMeta> fields = parseFields(schemaJson);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> in = incoming == null ? Map.of() : incoming;
        Map<String, Object> old = existingStored == null ? Map.of() : existingStored;

        for (FieldMeta f : fields) {
            boolean hasKey = in.containsKey(f.key);
            Object raw = in.get(f.key);
            if (f.sensitive) {
                if (!hasKey || raw == null || PLACEHOLDER.equals(String.valueOf(raw)) || !StringUtils.hasText(String.valueOf(
                    raw))) {
                    if (old.containsKey(f.key)) {
                        out.put(f.key, old.get(f.key));
                    } else if (f.required) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, 3008, "必填敏感字段缺失: " + f.key);
                    }
                    continue;
                }
                out.put(f.key, fieldEncryptor.encrypt(String.valueOf(raw)));
            } else {
                if (!hasKey) {
                    if (old.containsKey(f.key)) {
                        out.put(f.key, old.get(f.key));
                    } else if (f.required) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, 3008, "必填字段缺失: " + f.key);
                    }
                    continue;
                }
                if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
                    if (f.required) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, 3008, "必填字段不能为空: " + f.key);
                    }
                    continue;
                }
                out.put(f.key, raw);
            }
        }
        // 允许额外非 schema 键？禁止：只保留 schema 键
        return out;
    }

    public Map<String, Object> toDisplayFormData(String schemaJson, String storedJson, boolean editMode) {
        List<FieldMeta> fields = parseFields(schemaJson);
        Map<String, Object> stored = jsonMaps.readMap(storedJson);
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldMeta f : fields) {
            Object v = stored.get(f.key);
            if (v == null) {
                continue;
            }
            if (f.sensitive) {
                out.put(f.key, editMode ? PLACEHOLDER : MaskUtil.mask(peekPlain(v), f.mask, f.maskRule));
            } else {
                out.put(f.key, v);
            }
        }
        return out;
    }

    public List<IndexRow> indexRows(String schemaJson, Map<String, Object> storedFlat) {
        try {
            DeviceFormProcessor.PreparedForm prepared = DeviceFormProcessor.prepareCreate(
                jsonMaps.readMap(schemaJson), storedFlat,
                value -> fieldEncryptor.isEncrypted(value) ? value : fieldEncryptor.encrypt(value));
            return prepared.indexes().stream()
                .map(row -> new IndexRow(row.fieldKey(), row.valueText(),
                                         row.valueDecimal(), row.valueBoolean()))
                .toList();
        } catch (DeviceFormProcessor.DeviceFormException error) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3008, error.getMessage());
        }
    }

    public record IndexRow(String fieldKey, String valueText, BigDecimal valueDecimal, Boolean valueBoolean) {
    }

    public Map<String, FieldMeta> assertFormFilters(String schemaJson, Map<String, DeviceFormFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, FieldMeta> byKey = new HashMap<>();
        for (FieldMeta f : parseFields(schemaJson)) {
            byKey.put(f.key, f);
        }
        Map<String, FieldMeta> result = new HashMap<>();
        for (Map.Entry<String, DeviceFormFilter> entry : filters.entrySet()) {
            String key = entry.getKey();
            FieldMeta f = byKey.get(key);
            DeviceFormFilter filter = entry.getValue();
            if (f == null || !f.searchable || f.sensitive || "password".equals(f.type) || filter == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3009, "非法 formFilters: " + key);
            }
            boolean hasValue = StringUtils.hasText(filter.getValue());
            boolean hasNumber = filter.getNumber() != null;
            if (Set.of("int", "float").contains(f.type)) {
                if (!hasNumber || hasValue || !Set.of("GT",
                                                      "GE",
                                                      "EQ",
                                                      "NE",
                                                      "LE",
                                                      "LT").contains(filter.getOperator())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST,
                                                3009,
                                                "数值 formFilters 须提供合法 operator/number: " + key);
                }
            } else if (!hasValue || hasNumber || StringUtils.hasText(filter.getOperator())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3009, "非法 formFilters 条件: " + key);
            } else if ("bool".equals(f.type) && !Set.of("true", "false").contains(filter.getValue())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3009, "布尔 formFilters 须为 true/false: " + key);
            }
            result.put(key, f);
        }
        return result;
    }

    private String peekPlain(Object stored) {
        String s = String.valueOf(stored);
        try {
            return fieldEncryptor.isEncrypted(s) ? fieldEncryptor.decrypt(s) : s;
        } catch (Exception e) {
            log.warn("设备动态表单敏感字段解密失败，已降级为全掩码", e);
            return PLACEHOLDER;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o != null && Boolean.parseBoolean(o.toString());
    }

    public static class FieldMeta {
        public String key;
        public String type;
        public boolean required;
        public boolean searchable;
        public boolean sensitive;
        public boolean listVisible;
        public String mask;
        public Map<String, Object> maskRule;
    }
}

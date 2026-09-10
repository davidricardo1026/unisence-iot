package com.unisence.iot.timeseries;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 物模型事件物理表名与参数列名的唯一实现（event-storage-design.md §4.1 / §4.2）。
 */
public final class EventTableNames {

    public static final String PREFIX = "evt_";

    public static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,49}$");

    public static final Pattern PRODUCT_KEY = Pattern.compile("^[a-z][a-z0-9]{5}$");

    private static final Set<String> RESERVED = Set.of(
        "time", "product_key", "device_code", "event_type", "msg_id");

    private EventTableNames() {
    }

    public static String physicalTable(String productKey, String identifier) {
        requireProductKey(productKey);
        requireIdentifier(identifier);
        return PREFIX + productKey + "_" + identifier.toLowerCase(Locale.ROOT);
    }

    public static String columnName(String paramIdentifier) {
        requireIdentifier(paramIdentifier);
        String id = paramIdentifier.toLowerCase(Locale.ROOT);
        if (RESERVED.contains(id)) {
            throw new IllegalArgumentException("参数 identifier 与固定列冲突: " + id);
        }
        return "p_" + id;
    }

    public static boolean reserved(String identifier) {
        return identifier != null && RESERVED.contains(identifier.toLowerCase(Locale.ROOT));
    }

    public static void requireProductKey(String productKey) {
        if (productKey == null || !PRODUCT_KEY.matcher(productKey).matches()) {
            throw new IllegalArgumentException("product_key 必须为 6 位小写产品编码: " + productKey);
        }
    }

    public static void requireIdentifier(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("identifier 格式非法: " + identifier);
        }
    }
}

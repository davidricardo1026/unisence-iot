package com.unisence.iot.metadata;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表单值的递归复制与冻结（metadata-sync-bus.md §6.4、§13.3）。
 *
 * <p>缓存值会被<b>多个并发消息线程共享</b>，还会被 Groovy 规则脚本读到。因此必须：
 * <ul>
 *   <li><b>深复制</b> —— 直接引用数据库读出的可变 Map，某条规则脚本改一下就污染了所有后续消息；</li>
 *   <li><b>不可变</b> —— 冻结后即便脚本尝试写入也会立刻失败，而不是产生难以复现的串扰；</li>
 *   <li><b>类型受限</b> —— 只允许 null / 标量 / String 键 Map / List。放行任意对象等于把
 *       任意 Java 类型暴露给沙箱脚本。</li>
 * </ul>
 */
public final class MetadataValueFreezer {

    /**
     * 表单是扁平 key 结构，允许少量嵌套即可；超深结构多半是脏数据或攻击载荷。
     */
    private static final int MAX_DEPTH = 8;

    private MetadataValueFreezer() {
    }

    public static Map<String, Object> freezeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return freezeMap(source, 1, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source, int depth, Set<Object> visiting) {
        requireDepth(depth);
        // 用身份集合而不是 equals 集合检测循环：JSON 反序列化出的两个内容相同但独立的 Map
        // 不是循环，按 equals 判断会把它误判成环
        if (!visiting.add(source)) {
            throw new IllegalArgumentException("表单值存在循环引用");
        }
        try {
            Map<String, Object> copy = new LinkedHashMap<>(source.size());
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("表单值的键不能为 null");
                }
                copy.put(entry.getKey(), freezeValue(entry.getValue(), depth, visiting));
            }
            // 不用 Map.copyOf：它拒绝 null 值，而表单里「字段存在但未填」就是合法的 null
            return java.util.Collections.unmodifiableMap(copy);
        } finally {
            visiting.remove(source);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object freezeValue(Object value, int depth, Set<Object> visiting) {
        switch (value) {
            case null -> {
                return null;
            }
            case String s -> {
                return s;
            }
            case Number n -> {
                return n;
            }
            case Boolean b -> {
                return b;
            }
            case Map<?, ?> map -> {
                for (Object key : map.keySet()) {
                    if (!(key instanceof String)) {
                        throw new IllegalArgumentException(
                            "表单值的嵌套 Map 只允许 String 键，实际: " + key.getClass().getName());
                    }
                }
                return freezeMap((Map<String, Object>) map, depth + 1, visiting);
            }
            case List<?> list -> {
                requireDepth(depth + 1);
                if (!visiting.add(list)) {
                    throw new IllegalArgumentException("表单值存在循环引用");
                }
                try {
                    List<Object> copy = new ArrayList<>(list.size());
                    for (Object item : list) {
                        copy.add(freezeValue(item, depth + 1, visiting));
                    }
                    // 同上：List.copyOf 拒绝 null 元素
                    return java.util.Collections.unmodifiableList(copy);
                } finally {
                    visiting.remove(list);
                }
            }
            default -> throw new IllegalArgumentException(
                "表单值出现非法类型: " + value.getClass().getName());
        }
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("表单值嵌套深度超过上限 " + MAX_DEPTH);
        }
    }
}

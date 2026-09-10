package com.unisence.iot.metadata;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 把落库的设备表单值投影成<b>规则可见</b>的安全子集（metadata-sync-bus.md §6.4）。
 *
 * <p>三条剔除规则，每条都对应一类真实泄露路径：
 * <ol>
 *   <li><b>{@code sensitive=true} 整项剔除</b> —— 这些值会进 Redis L2、也会被 Groovy 脚本读到，
 *       任一处泄露都不可接受。剔除的是<b>整个键</b>而不是脱敏成掩码，因为掩码值仍会随缓存外流；</li>
 *   <li><b>{@code enc:v*:} 加密信封剔除</b> —— 即便字段被误标成非敏感，密文本身也不该出现在缓存里；</li>
 *   <li><b>schema 已删除的孤儿 key 剔除</b> —— 表单删字段后，设备行里可能还残留旧值，
 *       它们既不可解释也不该继续对规则可见。</li>
 * </ol>
 *
 * <p>通过筛选的值再交给 {@link MetadataValueFreezer} 递归复制并冻结。
 */
@Slf4j
public final class DeviceFormProjector {

    /**
     * 字段加密信封前缀，形如 {@code enc:v1:...}（field-encryption.md）。
     */
    private static final String ENCRYPTED_PREFIX = "enc:v";

    private DeviceFormProjector() {
    }

    /**
     * @param product 设备所属产品（来自本批固定使用的根快照）
     * @param stored  {@code us_iot_device.device_form_data} 反序列化后的原始 Map
     * @return 不可变、无敏感项的投影
     */
    public static Map<String, Object> project(ProductRuntimeMeta product, Map<String, Object> stored) {
        if (product == null || stored == null || stored.isEmpty()) {
            return Map.of();
        }
        String productKey = product.productKey();
        Set<String> visibleKeys = product.ruleVisibleFormKeys();
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stored.entrySet()) {
            if (!visibleKeys.contains(entry.getKey())) {
                // 敏感字段与孤儿 key 都落在这个分支：schema 里的可见 key 是唯一白名单
                continue;
            }
            if (entry.getValue() instanceof String text && text.startsWith(ENCRYPTED_PREFIX)) {
                log.warn("非敏感字段出现加密信封，已剔除: productKey={} field={}", productKey, entry.getKey());
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }
        return MetadataValueFreezer.freezeMap(filtered);
    }
}

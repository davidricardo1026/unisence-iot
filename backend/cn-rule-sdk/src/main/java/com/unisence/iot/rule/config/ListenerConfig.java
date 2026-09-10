package com.unisence.iot.rule.config;

import java.util.Set;

/**
 * 两张规则表 {@code listener_config} 列的字段级模型。
 *
 * <p>产品范围不在此列——它由 {@code us_iot_rule_instant_product} / {@code us_iot_rule_window_product} 关联表承载，
 * 以便「按产品反查规则」走索引而不是扫 JSON。
 *
 * @param identifiers 属性或事件 identifier 白名单；保存的规则至少包含一个 identifier。
 */
public record ListenerConfig(Set<String> identifiers) {

    public static final ListenerConfig ALL = new ListenerConfig(Set.of());

    public ListenerConfig {
        identifiers = identifiers == null ? Set.of() : Set.copyOf(identifiers);
    }

    public boolean matchesAll() {
        return identifiers.isEmpty();
    }
}

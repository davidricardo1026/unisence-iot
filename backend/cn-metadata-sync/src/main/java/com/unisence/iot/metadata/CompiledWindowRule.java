package com.unisence.iot.metadata;

import com.unisence.iot.rule.compiler.CompiledRuleScript;
import com.unisence.iot.rule.config.AggregateConfig;
import com.unisence.iot.rule.config.WindowConfig;
import com.unisence.iot.rule.config.WindowRuleDefinition;
import com.unisence.iot.rule.sdk.RuleFilter;

import java.util.List;
import java.util.Set;

/**
 * 已编译的窗口规则：消息累加，窗口到期结算时判档。
 *
 * <p>每 (规则, 设备, 窗口) 一份累加器 —— 这是 State Store 体积、changelog 吞吐、
 * PVC 规格与 restore 时间的<b>唯一来源</b>。「有几条窗口规则」因此直接等于容量，
 * 这也是两类规则分表的核心理由之一。
 */
public record CompiledWindowRule(
    WindowRuleDefinition definition,
    long revision,
    String scriptSha256,
    Set<Long> productIds,
    CompiledRuleScript<RuleFilter> filter,
    List<CompiledLevel> levels) implements CompiledRule {

    public CompiledWindowRule {
        if (definition == null || filter == null) {
            throw new IllegalArgumentException("规则定义与过滤脚本编译产物均不能为空");
        }
        productIds = productIds == null ? Set.of() : Set.copyOf(productIds);
        levels = levels == null ? List.of() : List.copyOf(levels);
    }

    public WindowConfig window() {
        return definition.window();
    }

    public AggregateConfig aggregate() {
        return definition.aggregate();
    }
}

package com.unisence.iot.metadata;

import com.unisence.iot.rule.compiler.CompiledRuleScript;
import com.unisence.iot.rule.config.InstantRuleDefinition;
import com.unisence.iot.rule.sdk.RuleFilter;

import java.util.List;
import java.util.Set;

/**
 * 已编译的即时规则：逐条消息判档，运行期零窗口状态。
 */
public record CompiledInstantRule(
    InstantRuleDefinition definition,
    long revision,
    String scriptSha256,
    Set<Long> productIds,
    CompiledRuleScript<RuleFilter> filter,
    List<CompiledLevel> levels) implements CompiledRule {

    public CompiledInstantRule {
        if (definition == null || filter == null) {
            throw new IllegalArgumentException("规则定义与过滤脚本编译产物均不能为空");
        }
        productIds = productIds == null ? Set.of() : Set.copyOf(productIds);
        levels = levels == null ? List.of() : List.copyOf(levels);
    }
}

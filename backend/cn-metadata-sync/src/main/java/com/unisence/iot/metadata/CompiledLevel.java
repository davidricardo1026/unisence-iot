package com.unisence.iot.metadata;

import com.unisence.iot.rule.compiler.CompiledRuleScript;
import com.unisence.iot.rule.config.ConditionKind;
import com.unisence.iot.rule.config.LevelDefinition;
import com.unisence.iot.rule.sdk.RuleFilter;
import com.unisence.iot.rule.sdk.RuleOutput;

import java.util.List;

/**
 * 一个已编译的档位：配置 + 该档位自己的条件与输出脚本。
 *
 * @param condition {@code conditionKind=SCRIPT} 时非空，{@code THRESHOLD} 时为 null。
 *                  复用 {@link RuleFilter} 接口 —— 两者的签名与约束完全相同
 *                  （读上下文、返回 Boolean、不得有副作用）
 * @param output        档位跃迁时执行的输出脚本，非空。<b>每个档位各有一份</b>：
 *                      「危急」与「预警」的文案、字段、下游动作本就不同，
 *                      共用一份脚本只会逼作者在脚本里按 severity 写 if-else
 * @param targets       该档位绑定的 Kafka 输出，按 {@code outputId ASC}，非空
 * @param staticHeaders 六项静态输出 header 的预编码字节
 */
public record CompiledLevel(
    LevelDefinition definition,
    CompiledRuleScript<RuleFilter> condition,
    CompiledRuleScript<RuleOutput> output,
    List<KafkaOutputTarget> targets,
    LevelHeaderBytes staticHeaders) {

    public CompiledLevel {
        if (definition == null || output == null) {
            throw new IllegalArgumentException("档位定义与输出脚本编译产物均不能为空");
        }
        if (definition.conditionKind() == ConditionKind.SCRIPT && condition == null) {
            throw new IllegalArgumentException(
                "conditionKind=SCRIPT 的档位缺少条件脚本编译产物: levelCode=" + definition.levelCode());
        }
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("档位未绑定 Kafka 输出: " + definition.levelCode());
        }
        if (staticHeaders == null) {
            throw new IllegalArgumentException("档位静态 header 不能为空: levelCode=" + definition.levelCode());
        }
        targets = List.copyOf(targets);
    }

    public String levelCode() {
        return definition.levelCode();
    }

    public int severity() {
        return definition.severity();
    }
}

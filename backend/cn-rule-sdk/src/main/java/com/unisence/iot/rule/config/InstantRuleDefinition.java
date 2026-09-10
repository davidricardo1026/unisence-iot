package com.unisence.iot.rule.config;

import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.sdk.ErrorPolicy;

import java.util.List;

/**
 * 即时规则：逐条消息判定，<b>运行期零状态</b>（{@code us_iot_rule_instant} 一行）。
 *
 * <p>不碰窗口 store、不碰 timer store、不参与 msgId 去重 —— 重复消息只会产生一条内容
 * 完全相同的重复输出，由下游按 {@code msgId + ruleId + revision} 判掉
 * （{@code dedup-scope-design.md} §一）。
 *
 * <p>唯一持有的状态是「当前档位」，且<b>只有真正跃迁过的 (规则, 设备) 才占一条</b>，
 * 与「每条消息一条」的去重完全不是一个量级。
 *
 * @param value 被监控信号的取值配置。{@code conditionKind=THRESHOLD} 的档位据此取数比较；
 *              全部档位都是 {@code SCRIPT} 时可为 null
 */
public record InstantRuleDefinition(
    long ruleId,
    String ruleCode,
    MessageType messageType,
    ListenerConfig listener,
    ValueConfig value,
    EmitMode emitMode,
    List<LevelDefinition> levels,
    ErrorPolicy errorPolicy,
    long revision) implements RuleDefinition {

    /**
     * 紧凑构造器里排序而不是要求调用方保证：admin 保存、engine 加载、校验用例三条路径
     * 都会构造它，任何一条忘了排序，运行期就会按错误顺序判档 —— 表现是「配了危急档
     * 却报了预警」，且没有任何异常。
     */
    public InstantRuleDefinition {
        if (emitMode == null) {
            throw new IllegalArgumentException("emitMode 不能为空");
        }
        listener = listener == null ? ListenerConfig.ALL : listener;
        errorPolicy = errorPolicy == null ? ErrorPolicy.DLQ_MESSAGE : errorPolicy;
        levels = LevelDefinition.sortedBySeverity(levels);
    }
}

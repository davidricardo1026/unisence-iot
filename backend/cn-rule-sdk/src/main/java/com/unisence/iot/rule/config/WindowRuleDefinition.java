package com.unisence.iot.rule.config;

import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.sdk.ErrorPolicy;

import java.util.List;

/**
 * 窗口规则：消息累加 + 窗口到期结算（{@code us_iot_rule_window} 一行）。
 *
 * <h2>判档只发生在窗口到期结算时</h2>
 * 消息到达时<b>只累加，不判档、不输出</b>。用未累加完的中间值判档会在窗口早期
 * 产生噪声 —— 一分钟均值窗口里的第一条消息，其「均值」就是那条消息本身，
 * 与规则想表达的「一分钟均值」无关。窗口的意义正是「攒够了再下结论」。
 *
 * <h2>一条规则一种计算方式</h2>
 * 「温度瞬时越限」与「温度一分钟均值越限」是两种计算，必须配成两条规则
 * （一条即时、一条窗口）。它们本就是两份状态，合进一条规则也省不掉，
 * 反而会让「有几条窗口规则」不再等于容量。
 *
 * @param window    窗口配置，非空
 * @param aggregate 聚合配置，非空。窗口不配聚合等于攒了数据无人计算，纯占窗口状态内存
 */
public record WindowRuleDefinition(
    long ruleId,
    String ruleCode,
    MessageType messageType,
    ListenerConfig listener,
    WindowConfig window,
    AggregateConfig aggregate,
    List<LevelDefinition> levels,
    ErrorPolicy errorPolicy,
    long revision) implements RuleDefinition {

    public WindowRuleDefinition {
        listener = listener == null ? ListenerConfig.ALL : listener;
        errorPolicy = errorPolicy == null ? ErrorPolicy.DLQ_MESSAGE : errorPolicy;
        levels = LevelDefinition.sortedBySeverity(levels);
    }
}

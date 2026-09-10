package com.unisence.iot.rule.config;

import com.unisence.iot.rule.sdk.ThresholdOperator;

import java.util.Comparator;
import java.util.List;

/**
 * 一个严重度档位（{@code us_iot_rule_level} 一行）。
 *
 * <h2>档位跃迁驱动输出</h2>
 * 同一 (规则, 设备) 在任一时刻恰好处于一个档位，含隐含的 {@code NORMAL}
 * （{@link #NORMAL_SEVERITY}）。<b>输出只在档位发生变化时产生</b>：
 *
 * <ul>
 *   <li>{@code NORMAL → CRITICAL} 或 {@code MINOR → CRITICAL} —— 升档告警</li>
 *   <li>{@code CRITICAL → MINOR} —— 降档</li>
 *   <li>{@code 任意档 → NORMAL} —— 恢复</li>
 *   <li>停留在同一档 —— <b>不产出</b></li>
 * </ul>
 *
 * <p>这一条同时消掉三个旧机制：告警风暴（持续高温 = 持续停留在同一档 = 不再产出）、
 * {@code terminal}（档位天然互斥，不存在「危急和预警同时报」）、
 * {@code RecoveryPolicy}（回落到 NORMAL 就是恢复，不需要开关）。
 *
 * @param severity       越小越严重；同规则内唯一。判档就是按它升序取第一个满足条件的档位，
 *                       因此它同时是「严重度」与「判定优先级」—— 两者本就该一致，
 *                       分成两个字段只会让人配出「预警比危急先判」的规则
 * @param operator       {@code conditionKind=THRESHOLD} 时非空
 * @param threshold      {@code conditionKind=THRESHOLD} 时非空
 * @param cooldownMillis 可选的额外节流；null 或 0 表示不限流。
 *                       档位跃迁本身已抑制「持续满足」的重复输出，这里只用于数值在
 *                       档位边界反复抖动的场景。<b>抑制的是输出，不是状态</b> ——
 *                       见 {@code RuleProcessor} 中「已宣告档位」的说明
 */
public record LevelDefinition(
    long levelId,
    String levelCode,
    int severity,
    ConditionKind conditionKind,
    ThresholdOperator operator,
    Double threshold,
    Long cooldownMillis) {

    /**
     * 隐含的「正常」档，不落库、不可配置。
     *
     * <p>取 {@link Integer#MAX_VALUE} 而不是 0 或 -1：severity 越小越严重，
     * 正常态是「最不严重」，因此必须是最大值。取 0 会让它比任何配置档更严重，
     * 判档时排在最前面，规则将永远处于正常态。
     */
    public static final int NORMAL_SEVERITY = Integer.MAX_VALUE;

    public boolean hasThreshold() {
        return operator != null && threshold != null;
    }

    public boolean cooldownEnabled() {
        return cooldownMillis != null && cooldownMillis > 0;
    }

    /**
     * 按 severity 升序冻结。null 元素直接剔除 —— 让一个 null 混进判档循环，
     * 表现是判档中途抛 NPE 并被错误策略吞成「规则执行失败」，而真实原因是配置装配漏了一格。
     */
    static List<LevelDefinition> sortedBySeverity(List<LevelDefinition> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.stream()
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingInt(LevelDefinition::severity))
            .toList();
    }
}

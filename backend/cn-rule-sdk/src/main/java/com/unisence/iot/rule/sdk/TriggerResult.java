package com.unisence.iot.rule.sdk;

/**
 * 本次输出的档位跃迁信息、窗口范围与聚合值。输出器只依赖它，禁止反查状态后端。
 *
 * @param levelCode     跃迁<b>后</b>的档位编码；{@code fireType=RECOVERY} 时为 null（回到正常态）
 * @param severity      跃迁后的严重度；恢复时为
 *                      {@link com.unisence.iot.rule.config.LevelDefinition#NORMAL_SEVERITY}
 * @param fromLevelCode 跃迁<b>前</b>已宣告的档位编码；此前处于正常态时为 null。
 *                      恢复输出主要靠它 —— 「哪个档位的告警恢复了」是下游关单的依据
 * @param windowed      是否来自窗口规则。即时规则的 windowStart/windowEnd 等于处理时刻，
 *                      不代表任何窗口
 * @param stateKey      逻辑状态 key（data-model.md §三）
 */
public record TriggerResult(
    long ruleId,
    long revision,
    FireType fireType,
    String levelCode,
    int severity,
    String fromLevelCode,
    boolean windowed,
    long windowStart,
    long windowEnd,
    StateScope stateScope,
    String stateKey,
    long triggeredAt,
    AggregateResult aggregate) {

    // ---------- 脚本便捷委托：rule-abstraction-design.md §六 的扁平字段视图 ----------

    /**
     * 是否为恢复输出。脚本据此分支产出「已恢复」文案。
     */
    public boolean recovered() {
        return fireType == FireType.RECOVERY;
    }

    /**
     * 是否为升档（含从正常态首次告警）。
     */
    public boolean raised() {
        return fireType == FireType.LEVEL_RAISE;
    }

    /**
     * 永不为空。
     */
    public long count() {
        return aggregate.count();
    }

    /**
     * 聚合类型不产出时为 null，脚本中直接参与数值比较会拆箱抛 NPE。
     */
    public Double sum() {
        return aggregate.sum();
    }

    public Double min() {
        return aggregate.min();
    }

    public Double max() {
        return aggregate.max();
    }

    public Double avg() {
        return aggregate.avg();
    }

    /**
     * 窗口时长毫秒；即时规则为 0。
     */
    public long windowMillis() {
        return windowEnd - windowStart;
    }
}

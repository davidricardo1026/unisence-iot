package com.unisence.iot.rule.sdk;

/**
 * 窗口类型：决定「哪些数据属于本次计算」（{@code window_config.type}）。
 *
 * <h2>为什么只剩两种</h2>
 * 原先有六种，其中四种在 2026-08-03 之前分别以不同方式失效：
 *
 * <ul>
 *   <li>{@code NONE} —— 「没有窗口」不是一种窗口。它现在由<b>即时规则表</b>表达，
 *       结构上就不可能配出「无窗口却带窗口参数」的规则；</li>
 *   <li>{@code COUNT} —— 三处半接线（countSize 零引用、窗口永不关闭、状态无回收路径），
 *       2026-08-02 起在保存期拒绝，此次直接删除；</li>
 *   <li>{@code SESSION} / {@code SLIDING_TIME} —— 状态上界无法在保存期静态判定，
 *       与「管理端必须做容量校验」直接冲突，一直未开放。</li>
 * </ul>
 *
 * <p>删除而不是留着拒绝：一个「能选、选了报错」的取值，与一个「能选、选了静默无效」
 * 只差一次「顺手支持一下」。要支持时再加，那时是有需求驱动的新增，
 * 而不是替一个从没人用过的枚举值补实现。
 */
public enum WindowType {

    /**
     * 固定长度且不重叠。
     */
    TUMBLING_TIME,
    /**
     * 固定长度、按 advance 步长重叠。一条消息同时落入 {@code ceil(size/advance)} 个窗口，
     * 状态与 state Topic 写入等比放大，因此放大因子在保存期封顶。
     */
    HOPPING_TIME;

    /**
     * 需要 advance 步长的窗口类型。
     */
    public boolean requiresAdvance() {
        return this == HOPPING_TIME;
    }
}

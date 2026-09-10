package com.unisence.iot.rule.config;

import com.unisence.iot.rule.sdk.StateScope;
import com.unisence.iot.rule.sdk.TimeMode;
import com.unisence.iot.rule.sdk.WindowType;

/**
 * {@code us_iot_rule_window.window_config} 的字段级模型。
 *
 * <p><b>时间单位统一为毫秒</b>，字段名一律带 {@code Millis} 后缀。
 * 契约初稿在 data-model.md（{@code size/advance/grace}）与 rule-abstraction-design.md
 * （{@code sizeSeconds/advanceSeconds}）中出现过两套命名与两种单位，此处收敛：
 * 运行时的窗口边界计算全部按毫秒进行，混用秒会引入成片的 1000 倍错误。
 *
 * <p>2026-08-03 删除两个字段：{@code countSize}（运行期零引用 —— 真正的阈值取自档位，
 * 用户按「每 N 条」配它不生效）与 {@code sessionGapMillis}（对应的 {@code SESSION}
 * 窗口本就在保存期被拒）。
 *
 * @param type            窗口类型；只有 {@code TUMBLING_TIME} / {@code HOPPING_TIME} 两种
 * @param sizeMillis      窗口长度，必填
 * @param advanceMillis   推进步长；{@code HOPPING_TIME} 必填
 * @param graceMillis     迟到数据宽限；{@code EVENT_TIME} 必填
 * @param retentionMillis 窗口状态在 worker 内存中的保留时长，必填，且不得小于 size + grace
 */
public record WindowConfig(
    WindowType type,
    TimeMode timeMode,
    Long sizeMillis,
    Long advanceMillis,
    Long graceMillis,
    Long retentionMillis,
    StateScope stateScope) {

    public WindowConfig {
        stateScope = stateScope == null ? StateScope.DEVICE : stateScope;
        timeMode = timeMode == null ? TimeMode.PROCESSING_TIME : timeMode;
    }

    /**
     * 窗口状态最少需要保留的时长。
     */
    public long minimumRetentionMillis() {
        long size = sizeMillis == null ? 0L : sizeMillis;
        long grace = graceMillis == null ? 0L : graceMillis;
        return size + grace;
    }
}

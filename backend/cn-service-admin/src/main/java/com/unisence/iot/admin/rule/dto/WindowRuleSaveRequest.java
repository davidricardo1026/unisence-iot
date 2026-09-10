package com.unisence.iot.admin.rule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 窗口规则保存请求：公共部分之外多出窗口与聚合两个配置对象，两者都必填。
 *
 * <p>「必填」在这里是结构性的（{@code @NotNull}），而取值合法性
 * （窗口类型、size/advance 关系、retention 下界、聚合取值类型）由
 * {@code RuleConfigValidator} 判定。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WindowRuleSaveRequest extends RuleSaveRequest {

    /**
     * {@code {type, timeMode, sizeMillis, advanceMillis, graceMillis, retentionMillis, stateScope}}。
     */
    @NotNull(message = "窗口配置不能为空")
    private Map<String, Object> windowConfig;

    /**
     * {@code {type, valueIdentifier, valueSource}}。
     *
     * <p>不可缺省：窗口不配聚合等于攒了数据无人计算，纯占 State Store。
     */
    @NotNull(message = "聚合配置不能为空")
    private Map<String, Object> aggregateConfig;
}

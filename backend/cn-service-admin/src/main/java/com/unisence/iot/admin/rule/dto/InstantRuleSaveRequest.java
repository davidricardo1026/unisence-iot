package com.unisence.iot.admin.rule.dto;

import com.unisence.iot.rule.config.EmitMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 即时规则保存请求：公共部分之外只多一个取值配置。
 *
 * <p>没有窗口与聚合字段 —— 这正是两类规则分开建模的意义：结构上就不可能给一条
 * 即时规则配上窗口参数，因此也不需要一条「即时规则的窗口配置必须为空」的校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InstantRuleSaveRequest extends RuleSaveRequest {

    /**
     * 被监控信号的取值配置 {@code {valueIdentifier, valueSource}}。
     *
     * <p>存在<b>阈值型档位</b>时必填 —— 运行期要靠它知道拿哪个字段与阈值比较。
     * 全部档位都是 {@code SCRIPT} 条件时可缺省：那时比较逻辑整个在脚本里。
     */
    private Map<String, Object> valueConfig;

    /**
     * 即时规则输出节奏。窗口规则没有该字段。
     */
    @NotNull(message = "输出模式不能为空")
    private EmitMode emitMode;
}

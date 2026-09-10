package com.unisence.iot.admin.rule.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一个档位的保存请求。
 *
 * <p>{@code thresholdConfig} 用 {@code Map} 承接而不是强类型字段：它的必填关系
 * （{@code THRESHOLD} 时 operator 与 threshold 都必填）由 {@code RuleConfigValidator}
 * 按契约统一判定，再定义一套 Bean Validation 注解等于把同一规则写两遍，且两处迟早不一致。
 *
 * <p>{@code levelId} 不在本 DTO 中：档位随规则整体替换保存（先删净再插入），
 * 客户端传一个 id 过来既无用也会诱导「按 id 局部更新」的错误预期。
 */
@Data
public class RuleLevelRequest {

    @NotBlank(message = "档位编码不能为空")
    @Size(max = 30, message = "档位编码最长 30 字符")
    private String levelCode;

    /**
     * 越小越严重；同一规则内唯一，同时也是判档顺序。
     *
     * <p>上界留在 {@code Short.MAX_VALUE - 1}：{@code Integer.MAX_VALUE} 是隐含正常档的
     * 保留值，而 DDL 用 {@code smallint} 存这一列。
     */
    @NotNull(message = "严重度不能为空")
    @Min(value = 0, message = "严重度不得小于 0")
    @Max(value = 32766, message = "严重度不得大于 32766")
    private Integer severity;

    /**
     * THRESHOLD / SCRIPT。SCRIPT 仅即时规则可用。
     */
    @NotBlank(message = "档位条件类型不能为空")
    private String conditionKind;

    /**
     * {@code {operator, threshold}}；{@code conditionKind=THRESHOLD} 时必填。
     */
    private Map<String, Object> thresholdConfig;

    /**
     * {@code conditionKind=SCRIPT} 时必填：返回 Boolean 的 Groovy 裸脚本。
     */
    private String conditionScript;

    @NotBlank(message = "档位输出脚本不能为空")
    private String outputScript;

    /**
     * 可选的额外节流；null 或 0 表示不限流。
     */
    @Min(value = 0, message = "冷却时长不可为负")
    private Long cooldownMillis;

    /**
     * 本档位绑定的 {@code purpose=RULE_OUTPUT} 输出定义，配置定义必填，至少一条且去重后长度不变。
     */
    @NotEmpty(message = "至少绑定一个 Kafka 输出")
    private List<@NotNull(message = "Kafka 输出 ID 不能为空") Long> kafkaOutputIds;
}

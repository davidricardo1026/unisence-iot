package com.unisence.iot.admin.rule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 透传路由规则新增/编辑请求。
 *
 * <p>没有脚本、档位、错误策略字段 —— 透传规则在结构上不可表达这些能力。
 * 保存事务校验顺序：messageType ∈ {property, event} → 产品存在且为普通产品 → 输出定义存在、未删除且 {@code purpose=ROUTE}
 * → 两个列表去重后长度不变 → 跨规则 {@code (productId, messageType, outputId)} 唯一。
 */
@Data
public class RuleRouteSaveRequest {

    @NotBlank(message = "规则编码不能为空")
    @Size(max = 50, message = "规则编码最长 50 字符")
    private String ruleCode;

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 120, message = "规则名称最长 120 字符")
    private String ruleName;

    /**
     * {@code property} / {@code event}（{@code MessageType.code()}）。
     */
    @NotBlank(message = "消息类型不能为空")
    private String messageType;

    @NotEmpty(message = "至少绑定一个产品")
    private List<@NotNull Long> productIds;

    @NotEmpty(message = "至少绑定一个 Kafka 输出")
    private List<@NotNull Long> kafkaOutputIds;

    /**
     * 编辑时必填的乐观锁版本；新增时忽略。
     */
    private Integer version;
}

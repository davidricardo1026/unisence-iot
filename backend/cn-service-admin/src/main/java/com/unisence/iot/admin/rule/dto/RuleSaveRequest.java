package com.unisence.iot.admin.rule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 两类规则保存请求的公共部分。
 *
 * <p>配置对象用 {@code Map<String, Object>} 承接而不是强类型 DTO：它们的合法组合由
 * {@code RuleConfigValidator} 按 {@code rule-config-schema.md} 统一判定，
 * 再定义一套 Bean Validation 注解等于把同一规则写两遍，且两处迟早不一致。
 * 这里只做「非空/长度/区间」这类与业务语义无关的结构性校验。
 *
 * <p>{@code scriptSha256}、{@code compileResult}、{@code revision}、{@code status}
 * <b>刻意不在本 DTO 中</b>：它们由服务端推进，接受客户端传入等于让调用方能伪造编译状态。
 *
 * <p><b>没有 {@code kind} 字段</b>：规则类别由接口路径决定
 * （{@code /iot/rules/instant} vs {@code /iot/rules/window}），
 * 而不是请求体里一个可填错的字符串 —— 那样后端还要处理「路径说即时、body 说窗口」。
 */
@Data
public abstract class RuleSaveRequest {

    /**
     * 创建时必填且同类规则内唯一；修改时忽略（创建后不可变）。
     */
    @Size(max = 50, message = "规则编码最长 50 字符")
    private String ruleCode;

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 120, message = "规则名称最长 120 字符")
    private String ruleName;

    @NotBlank(message = "消息类型不能为空")
    private String messageType;

    /**
     * 规则作用的普通产品。与规则定义在同一事务保存，禁止产生半绑定规则。
     */
    @NotEmpty(message = "至少选择一个产品")
    @Size(max = 100, message = "单条规则最多选择 100 个产品")
    private List<@NotNull(message = "产品 ID 不能为空") Long> productIds;

    /**
     * {@code {identifiers:[...]}}；缺省表示监听全部。
     */
    private Map<String, Object> listenerConfig;

    /**
     * 前置过滤脚本。<b>纯闸门</b>：只回答「这条消息与本规则相关吗」，
     * 告警条件由档位表达。留空保存为 {@code return true}。
     */
    private String filterScript;

    /**
     * 严重度档位。规则至少要有一个档位，否则它永远不会产生任何输出。
     */
    @NotEmpty(message = "至少配置一个档位")
    @Size(max = 10, message = "单条规则最多 10 个档位")
    private List<@Valid @NotNull(message = "档位不能为空") RuleLevelRequest> levels;

    /**
     * DLQ_MESSAGE / SKIP_RULE / DROP_MESSAGE；缺省为 DLQ_MESSAGE。
     */
    private String errorPolicy;

    /**
     * 乐观锁版本；修改时必填。
     */
    private Integer version;
}

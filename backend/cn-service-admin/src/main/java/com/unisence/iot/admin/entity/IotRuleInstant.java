package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_instant} —— 即时规则定义（逐条消息判档，运行期零窗口状态）。
 *
 * <p>JSON 列以字符串持有：MyBatis-Plus 的 JSON TypeHandler 需要为每个目标类型注册，
 * 而这些列的形状由 {@code rule-config-schema.md} 定义、由 {@code RuleConfigValidator} 校验，
 * 在 Service 层用 {@code RuleConfigCodec} 显式读写更直白，也避免把校验时机藏进 ORM。
 *
 * <p>{@code revision} 与 {@code script_sha256} 共同构成 engine 侧编译缓存键，
 * 因此这两个字段<b>只能由保存事务推进</b>，不接受客户端传入。
 *
 * <p><b>{@code rule_id} 与 {@code us_iot_rule_window} 不共享 ID 空间</b>：
 * 两张表各有独立的 {@code AUTO_INCREMENT}，全局身份是 {@code RuleKind + ruleId}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_instant")
public class IotRuleInstant extends BaseEntity implements RuleEntity {

    @TableId(type = IdType.AUTO)
    private Long ruleId;

    /**
     * 创建后不可变。
     */
    private String ruleCode;
    private String ruleName;
    /**
     * {@code MessageType.code()}，如 {@code property}。
     */
    private String messageType;

    /**
     * JSON：{@code {identifiers:[...]}}；产品绑定不存此列。
     */
    private String listenerConfig;
    /**
     * JSON：{@code {valueIdentifier, valueSource}}。
     * 全部档位都是 SCRIPT 条件时可为 NULL —— 那时平台不需要知道监控的是哪个字段。
     */
    private String valueConfig;
    /**
     * {@code EmitMode.name()}：{@code LEVEL_TRANSITION} / {@code EVERY_MATCH}。
     */
    private String emitMode;
    /**
     * 前置过滤脚本。<b>纯闸门</b>，不承担告警条件（告警条件由档位表达）。
     */
    private String filterScript;

    /**
     * filter 与各档位脚本按 severity 升序规范化拼接后的 SHA-256；服务端计算。
     */
    private String scriptSha256;
    /**
     * JSON，NOT NULL：最近一次成功保存的编译与静态检查摘要。
     */
    private String compileResult;

    private String errorPolicy;
    /**
     * 0-停用 1-启用；新建默认停用。
     */
    private Integer status;
    /**
     * 每次成功修改递增，用于编译缓存键；不代表发版。
     */
    private Long revision;
}

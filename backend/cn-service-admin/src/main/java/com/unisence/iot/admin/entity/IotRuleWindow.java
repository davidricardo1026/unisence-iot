package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_window} —— 窗口规则定义（消息累加 + 窗口到期结算判档）。
 *
 * <p>与 {@link IotRuleInstant} 只差 {@code window_config} / {@code aggregate_config} 两列。
 * <b>本表行数直接等于容量</b>：每 (规则, 设备, 窗口) 一份累加器，决定 State Store 体积、
 * changelog 吞吐、PVC 规格与故障恢复时间 —— 这正是两类规则分表的核心理由。
 *
 * <p>{@code revision} 除编译缓存键外还参与<b>窗口状态隔离</b>：改窗口参数必然 revision++，
 * 新旧状态天然不共享 stateKey，旧状态由收割器按过期时间回收。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_window")
public class IotRuleWindow extends BaseEntity implements RuleEntity {

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
     * JSON，NOT NULL：{@code {type, timeMode, sizeMillis, advanceMillis, graceMillis, retentionMillis, stateScope}}。
     */
    private String windowConfig;
    /**
     * JSON，NOT NULL：{@code {type, valueIdentifier, valueSource}}。
     * 窗口不配聚合等于攒了数据无人计算，因此这一列不可空。
     */
    private String aggregateConfig;
    /**
     * 前置过滤脚本。<b>纯闸门</b>，不承担告警条件（告警条件由档位表达）。
     */
    private String filterScript;

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
     * 每次成功修改递增；用于编译缓存键与窗口状态隔离，不代表发版。
     */
    private Long revision;
}

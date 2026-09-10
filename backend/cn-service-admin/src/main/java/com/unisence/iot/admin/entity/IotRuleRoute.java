package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_route} —— 透传路由规则：产品 × 消息类型 → Kafka 输出定义。
 *
 * <p>无脚本、无档位、无状态、无 revision；由 engine 在时序库写入确认后原样转发。
 * 与 {@code us_iot_rule_instant} / {@code us_iot_rule_window} 不共享 ID 空间，运行期身份 {@code R:ruleId}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_route")
public class IotRuleRoute extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long ruleId;

    /**
     * 创建后不可变。
     */
    private String ruleCode;
    private String ruleName;
    /**
     * {@code MessageType.code()}：{@code property} / {@code event}。
     */
    private String messageType;
    /**
     * 0 停用、1 启用；新建默认停用。
     */
    private Integer status;
}

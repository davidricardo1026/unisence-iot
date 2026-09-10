package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_route_kafka_output} —— 透传路由规则与 Kafka 输出定义的绑定（B 类关联，物理删除，仅创建审计）。
 *
 * <p>只能绑 {@code purpose=ROUTE}，至少一条。跨规则约束 {@code (product_id, message_type, output_id)} 唯一由保存事务保证。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_route_kafka_output")
public class IotRuleRouteKafkaOutput extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private Long outputId;
}

package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_level_kafka_output} —— 档位与 Kafka 输出定义的绑定（B 类关联，物理删除，仅创建审计）。
 *
 * <p>随规则聚合整体替换：保存时先删净该规则全部档位的绑定再插入。每个档位至少一条，只能绑 {@code purpose=RULE_OUTPUT}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_level_kafka_output")
public class IotRuleLevelKafkaOutput extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long levelId;
    private Long outputId;
}

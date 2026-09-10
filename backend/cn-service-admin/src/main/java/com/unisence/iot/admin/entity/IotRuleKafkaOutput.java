package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_kafka_output} —— 可复用的同集群 Kafka Topic 登记项。
 *
 * <p>一个 Topic 只有一个定义、一种用途、一种编码（{@code (target_topic, deleted)} 唯一）。
 * 不保存 broker、凭据或 producer 参数。已被档位或透传路由引用后只允许改 {@code outputName}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_kafka_output")
public class IotRuleKafkaOutput extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long outputId;

    /**
     * 创建后不可变。
     */
    private String outputCode;
    private String outputName;
    /**
     * {@code KafkaOutputPurpose.name()}：{@code RULE_OUTPUT} / {@code ROUTE}。
     */
    private String purpose;
    /**
     * 静态精确 Topic，禁止模板、通配符与正则。
     */
    private String targetTopic;
    /**
     * {@code OutputFormat.name()}：{@code JSON} / {@code MESSAGEPACK}。
     */
    private String format;
}

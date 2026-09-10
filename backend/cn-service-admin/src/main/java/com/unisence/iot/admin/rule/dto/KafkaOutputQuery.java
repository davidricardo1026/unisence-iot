package com.unisence.iot.admin.rule.dto;

import lombok.Data;

/**
 * Kafka 输出定义分页查询条件。
 */
@Data
public class KafkaOutputQuery {

    /**
     * {@code RULE_OUTPUT} / {@code ROUTE}；为空时不过滤。档位选择器传 {@code RULE_OUTPUT}，透传路由选择器传 {@code ROUTE}。
     */
    private String purpose;
    /**
     * 输出编码、名称或 Topic 的模糊匹配。
     */
    private String keyword;
}

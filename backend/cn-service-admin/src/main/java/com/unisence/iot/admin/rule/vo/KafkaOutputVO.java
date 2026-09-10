package com.unisence.iot.admin.rule.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Kafka 输出定义列表项 / 详情。
 */
@Data
public class KafkaOutputVO {

    private Long outputId;
    private String outputCode;
    private String outputName;
    /**
     * {@code RULE_OUTPUT} / {@code ROUTE}。
     */
    private String purpose;
    private String targetTopic;
    /**
     * {@code JSON} / {@code MESSAGEPACK}。
     */
    private String format;
    /**
     * 档位绑定数 + 透传路由绑定数。非零时前端禁用 Topic / 格式 / 用途 / 编码输入与删除按钮。
     */
    private Integer referenceCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer version;
}

package com.unisence.iot.admin.rule.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 透传路由规则列表项。
 */
@Data
public class RuleRouteVO {

    private Long ruleId;
    private String ruleCode;
    private String ruleName;
    private String messageType;
    private Integer status;
    private Integer productCount;
    /**
     * 绑定的目标 Topic 名，按 outputId 升序；列表页直接展示，无需再查详情。
     */
    private List<String> targetTopics;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer version;
}

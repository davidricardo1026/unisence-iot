package com.unisence.iot.admin.rule.dto;

import lombok.Data;

/**
 * 透传路由规则分页查询条件。
 */
@Data
public class RuleRouteQuery {

    /**
     * 规则名称或规则编码。
     */
    private String keyword;
    private String messageType;
    /**
     * 0-停用 1-启用。
     */
    private Integer status;
    /**
     * 按绑定产品过滤。
     */
    private Long productId;
    /**
     * 按绑定的输出定义过滤。
     */
    private Long outputId;
}

package com.unisence.iot.admin.rule.vo;

import lombok.Data;

/**
 * 规则绑定的产品摘要。
 */
@Data
public class RuleProductVO {

    private Long productId;
    private String productKey;
    private String productName;
}

package com.unisence.iot.admin.device.dto;

import lombok.Data;

@Data
public class ProductQuery {
    /**
     * 产品名称或产品标识的统一搜索词
     */
    private String keyword;
    private String productName;
    private String productKey;
    /**
     * 1普通 2标准；缺省仅普通
     */
    private Integer productType;
    private Long tagId;
    private Integer nodeType;
}

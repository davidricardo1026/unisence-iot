package com.unisence.iot.admin.device.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ProductVO {
    private Long productId;
    private String productKey;
    private String productName;
    private Integer nodeType;
    private Integer netType;
    private String vendor;
    private String model;
    private String icon;
    private String iconUrl;
    private String description;
    private Map<String, Object> attributes;
    private Map<String, Object> deviceFormSchema;
    private Integer productType;
    private Integer version;
    private LocalDateTime createTime;
    private List<TagVO> tags;
}

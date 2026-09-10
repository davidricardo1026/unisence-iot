package com.unisence.iot.admin.device.vo;

import lombok.Data;

@Data
public class TmPropertyVO {
    private Long propertyId;
    private Long productId;
    private String identifier;
    private String propertyName;
    private String dataType;
    private Integer accessMode;
    private String unit;
    private Integer retentionDays;
    private Integer version;
}

package com.unisence.iot.admin.device.dto;

import lombok.Data;

import java.util.Map;

@Data
public class DeviceQuery {
    private Long productId;
    private String deviceCode;
    private String deviceName;
    private Integer status;
    private Map<String, DeviceFormFilter> formFilters;
}

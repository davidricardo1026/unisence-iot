package com.unisence.iot.admin.device.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TmServiceVO {
    private Long serviceId;
    private Long productId;
    private String identifier;
    private String serviceName;
    private List<Map<String, Object>> inputParams;
    private List<Map<String, Object>> outputParams;
    private Integer callType;
    private Integer version;
}

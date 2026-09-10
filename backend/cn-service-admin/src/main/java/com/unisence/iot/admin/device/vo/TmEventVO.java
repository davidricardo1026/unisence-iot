package com.unisence.iot.admin.device.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TmEventVO {
    private Long eventId;
    private Long productId;
    private String identifier;
    private String eventName;
    private Integer eventType;
    private List<Map<String, Object>> inputParams;
    private Boolean ttlEnabled;
    private Integer ttlValue;
    private String ttlUnit;
    private Integer version;
}

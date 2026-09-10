package com.unisence.iot.admin.device.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class DeviceVO {
    private Long deviceId;
    private Long productId;
    private String productKey;
    private String productName;
    private String icon;
    private String iconUrl;
    private String deviceCode;
    private String deviceName;
    private Long gatewayId;
    private Integer nodeType;
    private Integer status;
    private LocalDateTime lastOnlineAt;
    private LocalDateTime activatedAt;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String address;
    private Map<String, Object> deviceFormData;
    private Integer version;
    private LocalDateTime createTime;
}

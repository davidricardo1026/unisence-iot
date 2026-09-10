package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
public class DeviceSaveRequest {

    @NotNull(message = "产品ID不能为空")
    private Long productId;

    @NotBlank(message = "设备编码不能为空")
    @Size(max = 50, message = "设备编码不能超过50字符")
    private String deviceCode;
    @Size(max = 100, message = "设备名称不能超过100字符")
    private String deviceName;
    @NotNull(message = "节点类型不能为空")
    private Integer nodeType;
    private Long gatewayId;
    @DecimalMin(value = "-180.0000000", message = "经度不能小于-180")
    @DecimalMax(value = "180.0000000", message = "经度不能大于180")
    private BigDecimal longitude;
    @DecimalMin(value = "-90.0000000", message = "纬度不能小于-90")
    @DecimalMax(value = "90.0000000", message = "纬度不能大于90")
    private BigDecimal latitude;
    @Size(max = 255, message = "地址不能超过255字符")
    private String address;
    private Map<String, Object> deviceFormData;
    private Integer version;
}

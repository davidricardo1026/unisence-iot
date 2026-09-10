package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class ProductSaveRequest {

    @NotBlank(message = "产品名称不能为空")
    @Size(max = 100, message = "产品名称不能超过100字符")
    private String productName;

    @NotNull(message = "节点类型不能为空")
    private Integer nodeType;

    private Integer netType;
    @Size(max = 100, message = "厂商不能超过100字符")
    private String vendor;
    @Size(max = 100, message = "型号不能超过100字符")
    private String model;
    @Size(max = 50, message = "图标标识不能超过50字符")
    private String icon;
    @Size(max = 255, message = "产品图片地址不能超过255字符")
    private String iconUrl;
    private String description;
    private Map<String, Object> attributes;
    private Map<String, Object> deviceFormSchema;
    private Integer productType;
    private Integer version;
}

package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TmPropertySaveRequest {

    @NotBlank(message = "标识符不能为空")
    @Size(max = 50, message = "标识符不能超过50字符")
    private String identifier;

    @NotBlank(message = "属性名称不能为空")
    @Size(max = 100, message = "属性名称不能超过100字符")
    private String propertyName;

    @NotBlank(message = "数据类型不能为空")
    @Size(max = 10, message = "数据类型不能超过10字符")
    private String dataType;

    private Integer accessMode;
    @Size(max = 20, message = "单位不能超过20字符")
    private String unit;
    @NotNull(message = "属性历史保留时间不能为空")
    private Integer retentionDays;
    private Integer version;
}

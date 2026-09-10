package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigUpdateRequest {
    @NotBlank(message = "参数名称不能为空")
    private String configName;

    @NotBlank(message = "参数键值不能为空")
    private String configValue;

    @NotNull(message = "系统内置标识不能为空")
    private Integer configType;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfigCreateRequest {
    @NotBlank(message = "参数名称不能为空")
    private String configName;

    @NotBlank(message = "参数键名不能为空")
    private String configKey;

    @NotBlank(message = "参数键值不能为空")
    private String configValue;

    private Integer configType = 0;
}

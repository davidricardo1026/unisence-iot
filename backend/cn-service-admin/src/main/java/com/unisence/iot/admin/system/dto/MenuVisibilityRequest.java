package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MenuVisibilityRequest {
    @NotNull(message = "启用状态不能为空")
    private Integer isVisible;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

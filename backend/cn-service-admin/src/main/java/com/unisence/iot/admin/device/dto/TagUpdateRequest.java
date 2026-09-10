package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TagUpdateRequest {

    @NotBlank(message = "标签值不能为空")
    private String tagValue;

    private String color;
    private String description;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

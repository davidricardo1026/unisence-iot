package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TagSaveRequest {

    @NotBlank(message = "标签键不能为空")
    @Size(max = 50, message = "标签键不能超过50字符")
    private String tagKey;

    @NotBlank(message = "标签值不能为空")
    @Size(max = 100, message = "标签值不能超过100字符")
    private String tagValue;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "颜色必须为 #RRGGBB", flags = Pattern.Flag.CASE_INSENSITIVE)
    private String color;
    @Size(max = 255, message = "标签说明不能超过255字符")
    private String description;
    private Integer version;
}

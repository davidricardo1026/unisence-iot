package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DictTypeUpdateRequest {
    @NotBlank(message = "字典名称不能为空")
    private String dictName;

    @NotBlank(message = "字典类型不能为空")
    private String dictType;

    @NotNull(message = "状态不能为空")
    private Integer status;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

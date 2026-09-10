package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DictDataUpdateRequest {
    @NotBlank(message = "字典标签不能为空")
    private String dictLabel;

    @NotBlank(message = "字典键值不能为空")
    private String dictValue;

    private Integer sortOrder;

    @NotNull(message = "状态不能为空")
    private Integer status;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

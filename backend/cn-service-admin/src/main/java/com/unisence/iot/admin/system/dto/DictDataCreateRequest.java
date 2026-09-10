package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DictDataCreateRequest {
    @NotBlank(message = "字典类型不能为空")
    private String dictType;

    @NotBlank(message = "字典标签不能为空")
    private String dictLabel;

    @NotBlank(message = "字典键值不能为空")
    private String dictValue;

    private Integer sortOrder = 0;
    private Integer status = 1;
}

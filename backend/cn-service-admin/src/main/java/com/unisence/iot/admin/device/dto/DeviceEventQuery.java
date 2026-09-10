package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeviceEventQuery {

    @NotBlank(message = "事件标识符不能为空")
    private String identifier;

    @NotNull(message = "开始时间不能为空")
    private Long from;

    @NotNull(message = "结束时间不能为空")
    private Long to;
}

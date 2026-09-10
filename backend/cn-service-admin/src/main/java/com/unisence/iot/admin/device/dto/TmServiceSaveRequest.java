package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TmServiceSaveRequest {

    @NotBlank(message = "标识符不能为空")
    @Size(max = 50, message = "标识符不能超过50字符")
    private String identifier;

    @NotBlank(message = "服务名称不能为空")
    @Size(max = 100, message = "服务名称不能超过100字符")
    private String serviceName;

    private List<Map<String, Object>> inputParams;
    private List<Map<String, Object>> outputParams;
    private Integer callType;
    private Integer version;
}

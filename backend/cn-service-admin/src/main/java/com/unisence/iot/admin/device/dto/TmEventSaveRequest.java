package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TmEventSaveRequest {

    @NotBlank(message = "标识符不能为空")
    @Size(max = 50, message = "标识符不能超过50字符")
    private String identifier;

    @NotBlank(message = "事件名称不能为空")
    @Size(max = 100, message = "事件名称不能超过100字符")
    private String eventName;

    private Integer eventType;
    private List<Map<String, Object>> inputParams;
    @NotNull(message = "请选择事件数据保留策略")
    private Boolean ttlEnabled;
    @Positive(message = "事件 TTL 数值必须为正整数")
    private Integer ttlValue;
    @Pattern(regexp = "[hd]", message = "事件 TTL 单位只允许 h 或 d")
    private String ttlUnit;
    private Integer version;
}

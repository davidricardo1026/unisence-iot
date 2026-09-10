package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConfigVO {
    private Long configId;
    private String configName;
    private String configKey;
    private String configValue;
    private Integer configType;
    private Integer version;
    private LocalDateTime createTime;
}

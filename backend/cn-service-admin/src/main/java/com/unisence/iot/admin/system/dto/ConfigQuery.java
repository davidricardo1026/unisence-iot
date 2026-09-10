package com.unisence.iot.admin.system.dto;

import lombok.Data;

/**
 * 参数配置查询条件
 */
@Data
public class ConfigQuery {
    private String configName;
    private String configKey;
    private Integer configType;
}

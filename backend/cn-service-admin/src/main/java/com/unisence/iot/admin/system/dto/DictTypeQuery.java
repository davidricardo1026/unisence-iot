package com.unisence.iot.admin.system.dto;

import lombok.Data;

/**
 * 字典类型查询条件
 */
@Data
public class DictTypeQuery {
    private String dictName;
    private String dictType;
    private Integer status;
}

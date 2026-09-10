package com.unisence.iot.admin.system.dto;

import lombok.Data;

/**
 * 字典数据查询条件
 */
@Data
public class DictDataQuery {
    private String dictType;
    private String dictLabel;
}

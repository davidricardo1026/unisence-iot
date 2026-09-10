package com.unisence.iot.admin.device.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个设备动态表单字段的类型化筛选条件。
 */
@Data
public class DeviceFormFilter {
    /**
     * 文本、布尔、枚举的查询值。
     */
    private String value;
    /**
     * 数值字段比较操作符：GT、GE、EQ、NE、LE、LT。
     */
    private String operator;
    /**
     * 数值字段的比较值。
     */
    private BigDecimal number;
}

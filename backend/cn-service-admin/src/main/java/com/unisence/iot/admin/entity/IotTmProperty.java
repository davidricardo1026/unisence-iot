package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;


@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_tm_property")
public class IotTmProperty extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long propertyId;
    private Long productId;
    private String identifier;
    private String propertyName;
    private String dataType;
    private Integer accessMode;
    private Integer retentionDays;
    /**
     * JSON 字符串
     */
    private String unit;
}

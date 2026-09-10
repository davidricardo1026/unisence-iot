package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_device_form_index")
public class IotDeviceFormIndex extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deviceId;
    private Long productId;
    private Integer schemaVersion;
    private String fieldKey;
    private String valueText;
    private BigDecimal valueDecimal;
    private Boolean valueBoolean;
}

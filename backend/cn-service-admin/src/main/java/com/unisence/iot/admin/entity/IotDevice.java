package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_device")
public class IotDevice extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long deviceId;
    private Long productId;
    private String deviceCode;
    private String deviceName;
    private Long gatewayId;
    private Integer nodeType;
    private Integer status;
    private LocalDateTime lastOnlineAt;
    private LocalDateTime activatedAt;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String address;
    /**
     * JSON 字符串；敏感字段为 enc:v*: 信封
     */
    private String deviceFormData;
}

package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_tm_service")
public class IotTmService extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long serviceId;
    private Long productId;
    private String identifier;
    private String serviceName;
    /**
     * JSON 字符串
     */
    private String inputParams;
    /**
     * JSON 字符串
     */
    private String outputParams;
    private Integer callType;
}

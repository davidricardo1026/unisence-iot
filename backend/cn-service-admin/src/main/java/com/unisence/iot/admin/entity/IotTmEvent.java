package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_tm_event")
public class IotTmEvent extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long eventId;
    private Long productId;
    private String identifier;
    private String eventName;
    private Integer eventType;
    private Boolean ttlEnabled;
    private Integer ttlValue;
    private String ttlUnit;
    /**
     * JSON 字符串
     */
    private String inputParams;
}

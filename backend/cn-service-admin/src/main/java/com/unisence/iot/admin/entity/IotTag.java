package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_tag")
public class IotTag extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long tagId;
    private String tagKey;
    private String tagValue;
    private String color;
    private String description;
}

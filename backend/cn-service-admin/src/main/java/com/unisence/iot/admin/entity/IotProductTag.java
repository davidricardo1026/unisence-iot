package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_product_tag")
public class IotProductTag extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private Long tagId;
}

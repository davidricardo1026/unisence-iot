package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_dict_type")
public class SysDictType extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long dictTypeId;
    private String dictName;
    private String dictType;
    private Integer status;
}

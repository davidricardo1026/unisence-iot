package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_dict_data")
public class SysDictData extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long dictDataId;
    private Integer sortOrder;
    private String dictLabel;
    private String dictValue;
    private String dictType;
    private Integer status;
}

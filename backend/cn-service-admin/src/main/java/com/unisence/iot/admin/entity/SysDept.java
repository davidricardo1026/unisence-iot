package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_dept")
public class SysDept extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long deptId;
    private Long parentId;
    private String ancestors;
    private String deptName;
    private Integer sortOrder;
    private String leader;
    private String phone;
    private Integer status;
}

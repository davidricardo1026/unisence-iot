package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_user")
public class SysUser extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long userId;
    private String userCode;
    private String userName;
    private String phone;
    private Integer status;
    private Long deptId;
}

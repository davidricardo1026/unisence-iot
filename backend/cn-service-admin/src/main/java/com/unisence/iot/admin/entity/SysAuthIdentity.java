package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_auth_identity")
public class SysAuthIdentity extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long identityId;
    private Long userId;
    private String identityType;
    private String identifier;
    private String credential;
}

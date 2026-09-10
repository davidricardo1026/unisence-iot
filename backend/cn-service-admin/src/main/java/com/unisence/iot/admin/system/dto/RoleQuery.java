package com.unisence.iot.admin.system.dto;

import lombok.Data;

/**
 * 角色查询条件
 */
@Data
public class RoleQuery {
    private String roleName;
    private String roleCode;
    private Integer status;
}

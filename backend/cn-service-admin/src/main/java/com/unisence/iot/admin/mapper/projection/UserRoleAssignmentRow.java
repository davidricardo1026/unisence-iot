package com.unisence.iot.admin.mapper.projection;

import lombok.Data;

/**
 * 用户与其已绑定角色的批量查询结果，仅供用户分页结果组装使用。
 */
@Data
public class UserRoleAssignmentRow {
    private Long userId;
    private Long roleId;
    private String roleName;
}

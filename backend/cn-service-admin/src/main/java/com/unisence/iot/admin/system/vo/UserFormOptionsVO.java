package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.util.List;

@Data
public class UserFormOptionsVO {
    private List<DeptTreeVO> departments;
    private List<RoleVO> roles;
}

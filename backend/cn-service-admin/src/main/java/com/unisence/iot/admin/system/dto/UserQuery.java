package com.unisence.iot.admin.system.dto;

import lombok.Data;

/**
 * 用户查询条件
 */
@Data
public class UserQuery {
    private String userCode;
    private String userName;
    private String phone;
    private Integer status;
    private Long deptId;
}

package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RoleVO {
    private Long roleId;
    private String roleName;
    private String roleCode;
    private Integer status;
    private LocalDateTime createTime;
    private Integer version;
    private List<Long> menuIds;
}

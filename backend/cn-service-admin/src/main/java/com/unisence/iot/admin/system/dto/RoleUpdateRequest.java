package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RoleUpdateRequest {
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    @NotNull(message = "状态不能为空")
    private Integer status;

    private List<Long> menuIds;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

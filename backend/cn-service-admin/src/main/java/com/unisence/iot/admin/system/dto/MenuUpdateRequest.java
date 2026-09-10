package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MenuUpdateRequest {

    @NotNull(message = "父菜单不能为空")
    private Long parentId;

    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 100, message = "菜单名称长度不能超过 100")
    private String menuName;

    @NotBlank(message = "菜单类型不能为空")
    @Pattern(regexp = "[DMCF]", message = "菜单类型只能是 D-模块、M-目录、C-菜单、F-按钮")
    private String menuType;

    @Size(max = 255, message = "路由路径长度不能超过 255")
    private String path;

    @Size(max = 255, message = "组件路径长度不能超过 255")
    private String component;

    @Size(max = 100, message = "权限标识长度不能超过 100")
    private String perms;

    @Size(max = 100, message = "菜单图标长度不能超过 100")
    private String icon;

    private Integer sortOrder;

    private Integer isVisible;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}

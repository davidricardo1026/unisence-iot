package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.RoleCreateRequest;
import com.unisence.iot.admin.system.dto.RoleQuery;
import com.unisence.iot.admin.system.dto.RoleUpdateRequest;
import com.unisence.iot.admin.system.service.SysRoleService;
import com.unisence.iot.admin.system.vo.RoleVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService roleService;

    @GetMapping
    @SaCheckPermission("sys:role:list")
    public PageResult<RoleVO> listRoles(PageRequest<RoleQuery> request) {
        return roleService.pageRoles(request);
    }

    @GetMapping("/{roleId}")
    @SaCheckPermission(value = {"sys:role:query", "sys:role:edit"}, mode = SaMode.OR)
    public RoleVO getRole(@PathVariable Long roleId) {
        return roleService.getRole(roleId);
    }

    @GetMapping("/form-options")
    @SaCheckPermission(value = {"sys:role:add", "sys:role:edit"}, mode = SaMode.OR)
    public com.unisence.iot.admin.system.vo.RoleFormOptionsVO getFormOptions() {
        return roleService.getFormOptions();
    }

    @OperLog(title = "角色管理", businessType = BusinessType.INSERT)
    @PostMapping
    @SaCheckPermission("sys:role:add")
    public Long createRole(@RequestBody @Valid RoleCreateRequest request) {
        return roleService.createRole(request);
    }

    @OperLog(title = "角色管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{roleId}")
    @SaCheckPermission("sys:role:edit")
    public void updateRole(@PathVariable Long roleId, @RequestBody @Valid RoleUpdateRequest request) {
        roleService.updateRole(roleId, request);
    }

    @OperLog(title = "角色管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{roleId}")
    @SaCheckPermission("sys:role:delete")
    public void deleteRole(@PathVariable Long roleId) {
        roleService.deleteRole(roleId);
    }
}

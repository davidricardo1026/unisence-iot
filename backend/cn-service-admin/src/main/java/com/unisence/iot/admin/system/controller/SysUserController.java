package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.ResetPasswordRequest;
import com.unisence.iot.admin.system.dto.UserCreateRequest;
import com.unisence.iot.admin.system.dto.UserQuery;
import com.unisence.iot.admin.system.dto.UserUpdateRequest;
import com.unisence.iot.admin.system.service.SysUserService;
import com.unisence.iot.admin.system.vo.UserVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;

    @GetMapping
    @SaCheckPermission("sys:user:list")
    public PageResult<UserVO> listUsers(PageRequest<UserQuery> request) {
        return userService.pageUsers(request);
    }

    @GetMapping("/form-options")
    @SaCheckPermission(value = {"sys:user:add", "sys:user:edit"}, mode = SaMode.OR)
    public com.unisence.iot.admin.system.vo.UserFormOptionsVO getFormOptions() {
        return userService.getFormOptions();
    }

    @OperLog(title = "用户管理", businessType = BusinessType.INSERT)
    @PostMapping
    @SaCheckPermission("sys:user:add")
    public Long createUser(@RequestBody @Valid UserCreateRequest request) {
        return userService.createUser(request);
    }

    @OperLog(title = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{userId}")
    @SaCheckPermission("sys:user:edit")
    public void updateUser(@PathVariable Long userId, @RequestBody @Valid UserUpdateRequest request) {
        userService.updateUser(userId, request);
    }

    @OperLog(title = "用户管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{userId}")
    @SaCheckPermission("sys:user:delete")
    public void deleteUser(@PathVariable Long userId) {
        userService.deleteUser(userId);
    }

    @OperLog(title = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{userId}/reset-password")
    @SaCheckPermission("sys:user:resetPwd")
    public void resetPassword(@PathVariable Long userId, @RequestBody @Valid ResetPasswordRequest request) {
        userService.resetPassword(userId, request);
    }

    @GetMapping("/profile")
    public UserVO getUserProfile() {
        return userService.getUserProfile();
    }
}

package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.DeptCreateRequest;
import com.unisence.iot.admin.system.dto.DeptUpdateRequest;
import com.unisence.iot.admin.system.service.SysDeptService;
import com.unisence.iot.admin.system.vo.DeptTreeVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/depts")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService deptService;

    @GetMapping("/tree")
    @SaCheckPermission("sys:dept:list")
    public List<DeptTreeVO> getDeptTree(
        @RequestParam(required = false) String deptName,
        @RequestParam(required = false) Integer status) {
        return deptService.getDeptTree(deptName, status);
    }

    @PostMapping
    @OperLog(title = "部门管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("sys:dept:add")
    public Long createDept(@RequestBody @Valid DeptCreateRequest request) {
        return deptService.createDept(request);
    }

    @PutMapping("/{deptId}")
    @OperLog(title = "部门管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("sys:dept:edit")
    public void updateDept(@PathVariable Long deptId, @RequestBody @Valid DeptUpdateRequest request) {
        deptService.updateDept(deptId, request);
    }

    @DeleteMapping("/{deptId}")
    @OperLog(title = "部门管理", businessType = BusinessType.DELETE)
    @SaCheckPermission("sys:dept:delete")
    public void deleteDept(@PathVariable Long deptId) {
        deptService.deleteDept(deptId);
    }
}

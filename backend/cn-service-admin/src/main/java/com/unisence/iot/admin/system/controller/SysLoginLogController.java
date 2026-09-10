package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.LoginLogQuery;
import com.unisence.iot.admin.system.service.SysLoginLogService;
import com.unisence.iot.admin.system.vo.LoginLogVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/login-logs")
@RequiredArgsConstructor
public class SysLoginLogController {

    private final SysLoginLogService loginLogService;

    @GetMapping
    @SaCheckPermission("sys:loginlog:list")
    public PageResult<LoginLogVO> pageLoginLogs(PageRequest<LoginLogQuery> request) {
        return loginLogService.pageLoginLogs(request);
    }

    @DeleteMapping("/{loginLogId}")
    @SaCheckPermission("sys:loginlog:remove")
    @OperLog(title = "登录日志", businessType = BusinessType.DELETE)
    public void deleteById(@PathVariable Long loginLogId) {
        loginLogService.deleteById(loginLogId);
    }

    @DeleteMapping
    @SaCheckPermission("sys:loginlog:remove")
    @OperLog(title = "登录日志", businessType = BusinessType.DELETE)
    public void batchDelete(@RequestBody List<Long> ids) {
        loginLogService.batchDelete(ids);
    }

    @DeleteMapping("/clean")
    @SaCheckPermission("sys:loginlog:clean")
    @OperLog(title = "登录日志", businessType = BusinessType.CLEAN)
    public void cleanAll() {
        loginLogService.cleanAll();
    }
}

package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.OperLogQuery;
import com.unisence.iot.admin.system.service.SysOperLogService;
import com.unisence.iot.admin.system.vo.OperLogVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/oper-logs")
@RequiredArgsConstructor
public class SysOperLogController {

    private final SysOperLogService operLogService;

    @GetMapping
    @SaCheckPermission("sys:operlog:list")
    public PageResult<OperLogVO> pageOperLogs(PageRequest<OperLogQuery> request) {
        return operLogService.pageOperLogs(request);
    }

    @GetMapping("/{operLogId}")
    @SaCheckPermission("sys:operlog:query")
    public OperLogVO detail(@PathVariable Long operLogId) {
        return operLogService.detail(operLogId);
    }

    @DeleteMapping
    @SaCheckPermission("sys:operlog:remove")
    @OperLog(title = "操作日志", businessType = BusinessType.DELETE)
    public void batchDelete(@RequestBody List<Long> ids) {
        operLogService.batchDelete(ids);
    }

    @DeleteMapping("/clean")
    @SaCheckPermission("sys:operlog:remove")
    @OperLog(title = "操作日志", businessType = BusinessType.CLEAN)
    public void cleanAll() {
        operLogService.cleanAll();
    }
}

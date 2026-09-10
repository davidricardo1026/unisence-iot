package com.unisence.iot.admin.metadata.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.metadata.MetadataRebuildService;
import com.unisence.iot.admin.metadata.MetadataSyncQueryService;
import com.unisence.iot.admin.metadata.dto.MetadataRebuildRequest;
import com.unisence.iot.admin.metadata.vo.MetadataSyncStatusVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 元数据总线运维入口（metadata-sync-bus.md §9.2、§9.3）。
 *
 * <p>查询与强制重建入口<b>只在 admin</b> —— engine 禁止任何 HTTP 端点，
 * 因此运维不可能绕过总线直接命令某个实例刷新。
 */
@RestController
@RequestMapping("/system/metadata-sync")
@RequiredArgsConstructor
public class MetadataSyncController {

    private final MetadataSyncQueryService queryService;
    private final MetadataRebuildService rebuildService;

    /**
     * 同时返回 MySQL 权威水位与各实例自报状态；落后判定以前者为基准。
     */
    @GetMapping("/status")
    @SaCheckPermission("sys:metadata:list")
    public MetadataSyncStatusVO status() {
        return queryService.status();
    }

    /**
     * 强制重建：在事务内分配<b>新</b> commit_seq 并记录指定范围，提交后走正常提示链路。
     *
     * <p>必须鉴权并记录操作日志 —— 全域重建会让所有实例重跑构建，是有成本的运维动作。
     */
    @PostMapping("/rebuild")
    @OperLog(title = "元数据同步", businessType = BusinessType.UPDATE)
    @SaCheckPermission("sys:metadata:rebuild")
    public long rebuild(@RequestBody @Valid MetadataRebuildRequest request) {
        return rebuildService.rebuild(
            request.getMetaKey(), request.getScopeIds(), StpUtil.getLoginIdAsLong());
    }

    /**
     * 重新提醒：重发当前水位的提示。
     *
     * <p>只唤醒落后实例；要让已同步实例重跑构建请用 {@link #rebuild}。
     */
    @PostMapping("/renotify")
    @OperLog(title = "元数据同步", businessType = BusinessType.OTHER)
    @SaCheckPermission("sys:metadata:rebuild")
    public long renotify() {
        return rebuildService.renotify();
    }
}

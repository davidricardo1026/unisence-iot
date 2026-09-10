package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.ConfigCreateRequest;
import com.unisence.iot.admin.system.dto.ConfigQuery;
import com.unisence.iot.admin.system.dto.ConfigUpdateRequest;
import com.unisence.iot.admin.system.service.SysConfigService;
import com.unisence.iot.admin.system.vo.ConfigVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/configs")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService configService;

    @GetMapping
    @SaCheckPermission("sys:config:list")
    public PageResult<ConfigVO> pageConfigs(PageRequest<ConfigQuery> request) {
        return configService.pageConfigs(request);
    }

    @PostMapping
    @OperLog(title = "参数配置", businessType = BusinessType.INSERT)
    @SaCheckPermission("sys:config:add")
    public Long createConfig(@RequestBody @Valid ConfigCreateRequest request) {
        return configService.createConfig(request);
    }

    @PutMapping("/{configId}")
    @OperLog(title = "参数配置", businessType = BusinessType.UPDATE)
    @SaCheckPermission("sys:config:edit")
    public void updateConfig(@PathVariable Long configId, @RequestBody @Valid ConfigUpdateRequest request) {
        configService.updateConfig(configId, request);
    }

    @DeleteMapping("/{configId}")
    @OperLog(title = "参数配置", businessType = BusinessType.DELETE)
    @SaCheckPermission("sys:config:delete")
    public void deleteConfig(@PathVariable Long configId) {
        configService.deleteConfig(configId);
    }

    @DeleteMapping("/cache")
    @OperLog(title = "参数配置", businessType = BusinessType.CLEAN)
    @SaCheckPermission("sys:config:clearCache")
    public void clearCache() {
        configService.clearCache();
    }
}

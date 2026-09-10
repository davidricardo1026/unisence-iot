package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.CacheClearRequest;
import com.unisence.iot.admin.system.service.CacheAdminService;
import com.unisence.iot.admin.system.vo.CacheRegistryItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/cache")
@RequiredArgsConstructor
public class CacheAdminController {

    private final CacheAdminService cacheAdminService;

    @GetMapping("/registry")
    @SaCheckPermission("sys:cache:list")
    public List<CacheRegistryItemVO> registry() {
        return cacheAdminService.listRegistry();
    }

    @PostMapping("/clear")
    @SaCheckPermission("sys:cache:clear")
    @OperLog(title = "缓存管理", businessType = BusinessType.CLEAN)
    public void clear(@RequestBody @Valid CacheClearRequest request) {
        cacheAdminService.clear(request);
    }
}

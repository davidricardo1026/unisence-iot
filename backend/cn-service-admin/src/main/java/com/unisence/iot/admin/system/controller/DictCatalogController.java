package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.system.service.DictCatalogService;
import com.unisence.iot.common.api.dict.DictCatalogVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/dict")
@RequiredArgsConstructor
public class DictCatalogController {

    private final DictCatalogService dictCatalogService;

    @GetMapping("/catalog")
    public DictCatalogVO getCatalog(@RequestParam(required = false) String sinceVersion) {
        StpUtil.checkLogin();
        return dictCatalogService.getCatalog(sinceVersion);
    }
}

package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.DictTypeCreateRequest;
import com.unisence.iot.admin.system.dto.DictTypeQuery;
import com.unisence.iot.admin.system.dto.DictTypeUpdateRequest;
import com.unisence.iot.admin.system.service.SysDictTypeService;
import com.unisence.iot.admin.system.vo.DictTypeVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/dict/types")
@RequiredArgsConstructor
public class SysDictTypeController {

    private final SysDictTypeService dictTypeService;

    @GetMapping
    @SaCheckPermission("sys:dict:list")
    public PageResult<DictTypeVO> pageDictTypes(PageRequest<DictTypeQuery> request) {
        return dictTypeService.pageDictTypes(request);
    }

    @PostMapping
    @OperLog(title = "字典管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("sys:dict:add")
    public Long createDictType(@RequestBody @Valid DictTypeCreateRequest request) {
        return dictTypeService.createDictType(request);
    }

    @PutMapping("/{dictTypeId}")
    @OperLog(title = "字典管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("sys:dict:edit")
    public void updateDictType(@PathVariable Long dictTypeId, @RequestBody @Valid DictTypeUpdateRequest request) {
        dictTypeService.updateDictType(dictTypeId, request);
    }

    @DeleteMapping("/{dictTypeId}")
    @OperLog(title = "字典管理", businessType = BusinessType.DELETE)
    @SaCheckPermission("sys:dict:delete")
    public void deleteDictType(@PathVariable Long dictTypeId) {
        dictTypeService.deleteDictType(dictTypeId);
    }
}

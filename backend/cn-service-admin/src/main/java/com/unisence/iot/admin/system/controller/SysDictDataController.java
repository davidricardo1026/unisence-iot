package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.DictDataCreateRequest;
import com.unisence.iot.admin.system.dto.DictDataQuery;
import com.unisence.iot.admin.system.dto.DictDataUpdateRequest;
import com.unisence.iot.admin.system.service.SysDictDataService;
import com.unisence.iot.admin.system.vo.DictDataVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/dict/data")
@RequiredArgsConstructor
public class SysDictDataController {

    private final SysDictDataService dictDataService;

    @GetMapping
    @SaCheckPermission("sys:dict:list")
    public PageResult<DictDataVO> pageDictData(PageRequest<DictDataQuery> request) {
        return dictDataService.pageDictData(request);
    }

    @GetMapping("/{dictType}")
    @SaCheckPermission("sys:dict:list")
    public List<DictDataVO> listByDictType(@PathVariable String dictType) {
        return dictDataService.listByDictType(dictType);
    }

    @PostMapping
    @SaCheckPermission("sys:dict:add")
    @OperLog(title = "字典数据", businessType = BusinessType.INSERT)
    public Long createDictData(@RequestBody @Valid DictDataCreateRequest request) {
        return dictDataService.createDictData(request);
    }

    @PutMapping("/{dictDataId}")
    @SaCheckPermission("sys:dict:edit")
    @OperLog(title = "字典数据", businessType = BusinessType.UPDATE)
    public void updateDictData(@PathVariable Long dictDataId, @RequestBody @Valid DictDataUpdateRequest request) {
        dictDataService.updateDictData(dictDataId, request);
    }

    @DeleteMapping("/{dictDataId}")
    @SaCheckPermission("sys:dict:delete")
    @OperLog(title = "字典数据", businessType = BusinessType.DELETE)
    public void deleteDictData(@PathVariable Long dictDataId) {
        dictDataService.deleteDictData(dictDataId);
    }
}

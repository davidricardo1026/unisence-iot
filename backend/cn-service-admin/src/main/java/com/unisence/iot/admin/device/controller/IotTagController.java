package com.unisence.iot.admin.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.device.dto.TagQuery;
import com.unisence.iot.admin.device.dto.TagSaveRequest;
import com.unisence.iot.admin.device.dto.TagUpdateRequest;
import com.unisence.iot.admin.device.service.IotTagService;
import com.unisence.iot.admin.device.vo.TagVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/iot/tags")
@RequiredArgsConstructor
public class IotTagController {

    private final IotTagService tagService;

    @GetMapping
    @SaCheckPermission("iot:tag:list")
    public PageResult<TagVO> pageTags(PageRequest<TagQuery> request) {
        return tagService.pageTags(request);
    }

    @PostMapping
    @OperLog(title = "标签管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:tag:add")
    public Long createTag(@RequestBody @Valid TagSaveRequest request) {
        return tagService.createTag(request);
    }

    @PutMapping("/{tagId}")
    @OperLog(title = "标签管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:tag:edit")
    public void updateTag(@PathVariable Long tagId, @RequestBody @Valid TagUpdateRequest request) {
        tagService.updateTag(tagId, request);
    }

    @DeleteMapping("/{tagId}")
    @OperLog(title = "标签管理", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:tag:remove")
    public void deleteTag(@PathVariable Long tagId) {
        tagService.deleteTag(tagId);
    }
}

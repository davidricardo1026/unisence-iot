package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.MenuBatchVisibilityRequest;
import com.unisence.iot.admin.system.dto.MenuCreateRequest;
import com.unisence.iot.admin.system.dto.MenuUpdateRequest;
import com.unisence.iot.admin.system.dto.MenuVisibilityRequest;
import com.unisence.iot.admin.system.service.SysMenuService;
import com.unisence.iot.admin.system.vo.MenuTreeVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/menus")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService menuService;

    @GetMapping("/tree")
    @SaCheckPermission("sys:menu:list")
    public List<MenuTreeVO> getMenuTree(
        @RequestParam(required = false) String menuName,
        @RequestParam(required = false) String perms,
        @RequestParam(required = false) Integer isVisible) {
        return menuService.getMenuTree(menuName, perms, isVisible);
    }

    @PutMapping("/{menuId}/visibility")
    @SaCheckPermission("sys:menu:edit")
    @OperLog(title = "菜单管理", businessType = BusinessType.UPDATE)
    public void updateVisibility(@PathVariable Long menuId, @RequestBody @Valid MenuVisibilityRequest request) {
        menuService.updateVisibility(menuId, request.getIsVisible(), request.getVersion());
    }

    @PutMapping("/batch-visibility")
    @SaCheckPermission("sys:menu:edit")
    @OperLog(title = "菜单管理", businessType = BusinessType.UPDATE)
    public void batchUpdateVisibility(@RequestBody @Valid MenuBatchVisibilityRequest request) {
        menuService.batchUpdateVisibility(request.getVisibleIds(), request.getItems());
    }

    @OperLog(title = "菜单管理", businessType = BusinessType.INSERT)
    @PostMapping
    @SaCheckPermission("sys:menu:add")
    public Long createMenu(@RequestBody @Valid MenuCreateRequest request) {
        return menuService.createMenu(request);
    }

    @OperLog(title = "菜单管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{menuId}")
    @SaCheckPermission("sys:menu:edit")
    public void updateMenu(@PathVariable Long menuId, @RequestBody @Valid MenuUpdateRequest request) {
        menuService.updateMenu(menuId, request);
    }

    @OperLog(title = "菜单管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{menuId}")
    @SaCheckPermission("sys:menu:delete")
    public void deleteMenu(@PathVariable Long menuId) {
        menuService.deleteMenu(menuId);
    }
}

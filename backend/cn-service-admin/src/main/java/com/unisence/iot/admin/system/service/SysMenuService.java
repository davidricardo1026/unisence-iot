package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.MenuBatchVisibilityRequest;
import com.unisence.iot.admin.system.dto.MenuCreateRequest;
import com.unisence.iot.admin.system.dto.MenuUpdateRequest;
import com.unisence.iot.admin.system.vo.MenuTreeVO;

import java.util.List;

public interface SysMenuService {

    List<MenuTreeVO> getMenuTree(String menuName, String perms, Integer isVisible);

    /** 侧边栏菜单树（仅 M/C 类型 + 可见） */
    List<MenuTreeVO> getSidebarMenuTree();

    /**
     * 👑 仅保留可见性修改功能
     */
    void updateVisibility(Long menuId, Integer isVisible, Integer version);

    /**
     * 👑 批量更新可见性
     */
    void batchUpdateVisibility(List<Long> visibleIds, List<MenuBatchVisibilityRequest.MenuVersionItem> items);

    /**
     * 新增菜单节点。
     *
     * @return 新建菜单主键
     */
    Long createMenu(MenuCreateRequest request);

    /**
     * 修改菜单节点（乐观锁）。
     */
    void updateMenu(Long menuId, MenuUpdateRequest request);

    /**
     * 删除菜单节点（逻辑删除）。存在子菜单或仍被角色绑定时拒绝。
     */
    void deleteMenu(Long menuId);
}

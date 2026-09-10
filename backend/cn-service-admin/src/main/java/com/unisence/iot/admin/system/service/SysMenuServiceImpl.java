package com.unisence.iot.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unisence.iot.admin.entity.SysMenu;
import com.unisence.iot.admin.entity.SysRoleMenu;
import com.unisence.iot.admin.mapper.SysMenuMapper;
import com.unisence.iot.admin.mapper.SysRoleMenuMapper;
import com.unisence.iot.admin.system.converter.SysMenuConverter;
import com.unisence.iot.admin.system.dto.MenuBatchVisibilityRequest;
import com.unisence.iot.admin.system.dto.MenuCreateRequest;
import com.unisence.iot.admin.system.dto.MenuUpdateRequest;
import com.unisence.iot.admin.system.vo.MenuTreeVO;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends BaseServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    /**
     * 顶级父节点占位 ID
     */
    private static final long ROOT_PARENT_ID = 0L;

    /**
     * 类型 → 合法父类型集合。空集合表示只能挂在顶级（{@code parentId = 0}）。
     * 对应契约层级链：{@code ROOT → D}、{@code D → M|C}、{@code M → C}、{@code C → F}。
     */
    private static final Map<String, Set<String>> ALLOWED_PARENT_TYPES = Map.of(
        "D", Set.of(),
        "M", Set.of("D"),
        "C", Set.of("D", "M"),
        "F", Set.of("C")
    );

    private final SysMenuMapper menuMapper;
    private final SysMenuConverter menuConverter;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserCacheService userCacheService;

    @Override
    public List<MenuTreeVO> getMenuTree(String menuName, String perms, Integer isVisible) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(menuName), SysMenu::getMenuName, menuName)
            .like(StringUtils.hasText(perms), SysMenu::getPerms, perms)
            .eq(isVisible != null, SysMenu::getIsVisible, isVisible)
            .orderByAsc(SysMenu::getSortOrder)
            .orderByAsc(SysMenu::getMenuId);
        List<SysMenu> menus = menuMapper.selectList(wrapper);
        return buildTree(menus);
    }

    @Override
    public List<MenuTreeVO> getSidebarMenuTree() {
        Long userId = StpUtil.getLoginIdAsLong();

        List<SysMenu> menus;
        if (StpUtil.hasPermission("*")) {
            // 👑 超级管理员：拉取所有类型的菜单（D, M, C, F）
            LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByAsc(SysMenu::getSortOrder)
                .orderByAsc(SysMenu::getMenuId);
            menus = menuMapper.selectList(wrapper);
        } else {
            // 普通用户：根据权限拉取
            menus = menuMapper.selectAccessibleMenusByUserId(userId);
        }

        return buildTree(menus);
    }

    @Override
    @Transactional
    public void updateVisibility(Long menuId, Integer isVisible, Integer version) {
        SysMenu menu = requireMenu(menuId);
        menu.setVersion(version);
        menu.setIsVisible(isVisible);

        // 👑 核心逻辑：父不可见，子就一定不可见
        if (isVisible != null && isVisible == 1) {
            // 如果尝试设置为可见，必须检查父级是否可见
            if (menu.getParentId() != null && menu.getParentId() != 0L) {
                SysMenu parent = menuMapper.selectById(menu.getParentId());
                if (parent != null && parent.getIsVisible() == 0) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, 2011, "上级菜单已禁用，无法将子项设为启用");
                }
            }
        }

        menuMapper.updateByIdWithVersionCheck(menu);

        // 如果当前节点被禁用，递归禁用所有子节点
        if (isVisible != null && isVisible == 0) {
            cascadeHideChildren(menuId);
        }

        // 👑 is_visible 参与 selectPermsByUserId 过滤，不清缓存则禁用的权限串会在 TTL 内继续生效
        userCacheService.evictAllPerms();
    }

    @Override
    @Transactional
    public void batchUpdateVisibility(List<Long> visibleIds, List<MenuBatchVisibilityRequest.MenuVersionItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Set<Long> visibleSet = new HashSet<>(visibleIds);

        for (MenuBatchVisibilityRequest.MenuVersionItem item : items) {
            SysMenu menu = new SysMenu();
            menu.setMenuId(item.getMenuId());
            menu.setVersion(item.getVersion());
            menu.setIsVisible(visibleSet.contains(item.getMenuId()) ? 1 : 0);

            // 👑 批量更新，由于前端已经根据树形逻辑计算好了可见性集合（父子联动），
            // 这里直接根据集合状态更新即可，不需要再执行 cascadeHideChildren。
            menuMapper.updateByIdWithVersionCheck(menu);
        }

        // 👑 同 updateVisibility：可见性变更即权限变更，必须失效权限点缓存
        userCacheService.evictAllPerms();
    }

    // ======================== 结构 CRUD ========================

    @Override
    @Transactional
    public Long createMenu(MenuCreateRequest request) {
        SysMenu parent = validateHierarchy(request.getMenuType(), request.getParentId());
        validateTypeFields(request.getMenuType(), request.getPath(), request.getComponent(), request.getPerms());
        assertPermsUnique(request.getPerms(), null);
        assertNameUniqueUnderParent(request.getParentId(), request.getMenuName(), null);

        SysMenu menu = menuConverter.toEntity(request);
        normalizeOptionalFields(menu);
        // 父不可见时，新建子节点强制不可见，保持与可见性父子约束一致
        if (parent != null && Integer.valueOf(0).equals(parent.getIsVisible())) {
            menu.setIsVisible(0);
        }
        menuMapper.insert(menu);

        userCacheService.evictAllPerms();
        return menu.getMenuId();
    }

    @Override
    @Transactional
    public void updateMenu(Long menuId, MenuUpdateRequest request) {
        SysMenu menu = requireMenu(menuId);
        // 成环校验须先于层级校验：父级指向自身时，「不能是自身」比「类型不匹配」更贴近用户意图
        assertNotSelfOrDescendant(menuId, request.getParentId());
        SysMenu parent = validateHierarchy(request.getMenuType(), request.getParentId());
        validateTypeFields(request.getMenuType(), request.getPath(), request.getComponent(), request.getPerms());
        assertPermsUnique(request.getPerms(), menuId);
        assertNameUniqueUnderParent(request.getParentId(), request.getMenuName(), menuId);

        // 父不可见时不允许把子项置为可见（与 updateVisibility 同一约束，复用 2011）
        if (Integer.valueOf(1).equals(request.getIsVisible())
            && parent != null && Integer.valueOf(0).equals(parent.getIsVisible())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2011, "上级菜单已禁用，无法将子项设为启用");
        }

        menuConverter.updateEntity(menu, request);
        normalizeOptionalFields(menu);
        menu.setVersion(request.getVersion());
        menuMapper.updateByIdWithVersionCheck(menu);

        // 改为禁用时，级联禁用整棵子树
        if (Integer.valueOf(0).equals(request.getIsVisible())) {
            cascadeHideChildren(menuId);
        }

        userCacheService.evictAllPerms();
    }

    @Override
    @Transactional
    public void deleteMenu(Long menuId) {
        requireMenu(menuId);

        Long childCount = menuMapper.selectCount(
            new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, menuId));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2012, "存在子菜单，无法删除");
        }

        Long boundCount = roleMenuMapper.selectCount(
            new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, menuId));
        if (boundCount != null && boundCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2027, "菜单已被角色绑定，无法删除");
        }

        // 👑 铁律：逻辑删除必须走 this.removeById（deleted = 主键ID）
        this.removeById(menuId);

        userCacheService.evictAllPerms();
    }

    // ======================== 校验 ========================

    /**
     * 校验类型与父级的层级关系。
     *
     * @return 父菜单实体；顶级节点返回 {@code null}
     */
    private SysMenu validateHierarchy(String menuType, Long parentId) {
        Set<String> allowedParentTypes = ALLOWED_PARENT_TYPES.get(menuType);
        if (allowedParentTypes == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2029, "菜单类型非法：" + menuType);
        }

        if (parentId == null || parentId == ROOT_PARENT_ID) {
            if (!allowedParentTypes.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 2029,
                                            "「" + typeLabel(menuType) + "」不能作为顶级节点，必须挂在"
                                                + typeLabels(allowedParentTypes) + "下");
            }
            return null;
        }

        SysMenu parent = menuMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2010, "父菜单不存在");
        }
        if (allowedParentTypes.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2029,
                                        "「" + typeLabel(menuType) + "」只能作为顶级节点");
        }
        if (!allowedParentTypes.contains(parent.getMenuType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2029,
                                        "「" + typeLabel(menuType) + "」不能挂在「"
                                            + typeLabel(parent.getMenuType()) + "」下，只能挂在"
                                            + typeLabels(allowedParentTypes) + "下");
        }
        return parent;
    }

    /**
     * 校验 path / component / perms 与菜单类型的匹配关系
     */
    private void validateTypeFields(String menuType, String path, String component, String perms) {
        boolean pathRequired = !"F".equals(menuType);
        boolean componentRequired = "C".equals(menuType);
        boolean permsRequired = "F".equals(menuType);
        // C 类型的 perms 选填，其余类型（D/M）必须为空
        boolean permsAllowed = permsRequired || "C".equals(menuType);

        assertFieldPresence(pathRequired, StringUtils.hasText(path), menuType, "路由路径");
        assertFieldPresence(componentRequired, StringUtils.hasText(component), menuType, "组件路径");
        if (permsAllowed) {
            assertFieldPresence(permsRequired, StringUtils.hasText(perms), menuType, "权限标识");
        } else if (StringUtils.hasText(perms)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2031,
                                        "「" + typeLabel(menuType) + "」不允许填写权限标识");
        }
    }

    private void assertFieldPresence(boolean required, boolean present, String menuType, String fieldLabel) {
        if (required && !present) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2031,
                                        "「" + typeLabel(menuType) + "」必须填写" + fieldLabel);
        }
        if (!required && present) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2031,
                                        "「" + typeLabel(menuType) + "」不允许填写" + fieldLabel);
        }
    }

    /**
     * 权限标识全局唯一（空白不参与校验，目录/模块本就无 perms）
     */
    private void assertPermsUnique(String perms, Long excludeMenuId) {
        if (!StringUtils.hasText(perms)) {
            return;
        }
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getPerms, perms);
        if (excludeMenuId != null) {
            wrapper.ne(SysMenu::getMenuId, excludeMenuId);
        }
        Long count = menuMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2009, "权限标识已存在");
        }
    }

    /**
     * 同一父级下菜单名称不可重复
     */
    private void assertNameUniqueUnderParent(Long parentId, String menuName, Long excludeMenuId) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getParentId, parentId == null ? ROOT_PARENT_ID : parentId)
            .eq(SysMenu::getMenuName, menuName);
        if (excludeMenuId != null) {
            wrapper.ne(SysMenu::getMenuId, excludeMenuId);
        }
        Long count = menuMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2030, "同级下菜单名称已存在");
        }
    }

    /**
     * 防止把菜单挂到自身或自身的子孙节点下形成环
     */
    private void assertNotSelfOrDescendant(Long menuId, Long newParentId) {
        if (newParentId == null || newParentId == ROOT_PARENT_ID) {
            return;
        }
        if (newParentId.equals(menuId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2028, "父菜单不能是自身");
        }

        // 自底向上回溯祖先链，若途经自身则说明目标父级位于自身子树内
        Set<Long> visited = new HashSet<>();
        Long cursor = newParentId;
        while (cursor != null && cursor != ROOT_PARENT_ID && visited.add(cursor)) {
            SysMenu ancestor = menuMapper.selectById(cursor);
            if (ancestor == null) {
                return;
            }
            if (menuId.equals(ancestor.getParentId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 2028, "父菜单不能是自身的子菜单");
            }
            cursor = ancestor.getParentId();
        }
    }

    /**
     * 可空字段归一为空串。
     * <p>
     * MyBatis-Plus 默认 {@code NOT_NULL} 更新策略会跳过 null 字段，
     * 若不归一，类型从 C 改为 M 时残留的 component 将无法被清除。
     * </p>
     */
    private void normalizeOptionalFields(SysMenu menu) {
        menu.setPath(StringUtils.hasText(menu.getPath()) ? menu.getPath() : "");
        menu.setComponent(StringUtils.hasText(menu.getComponent()) ? menu.getComponent() : "");
        menu.setPerms(StringUtils.hasText(menu.getPerms()) ? menu.getPerms() : "");
        menu.setIcon(StringUtils.hasText(menu.getIcon()) ? menu.getIcon() : "");
    }

    private static String typeLabel(String menuType) {
        return switch (menuType) {
            case "D" -> "模块";
            case "M" -> "目录";
            case "C" -> "菜单";
            case "F" -> "按钮";
            default -> menuType;
        };
    }

    private static String typeLabels(Set<String> menuTypes) {
        return menuTypes.stream().sorted().map(SysMenuServiceImpl::typeLabel)
            .collect(Collectors.joining("或", "「", "」"));
    }

    private void cascadeHideChildren(Long parentId) {
        List<SysMenu> children = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                                                           .eq(SysMenu::getParentId, parentId)
                                                           .eq(SysMenu::getIsVisible, 1)); // 仅处理当前可见的子项

        for (SysMenu child : children) {
            child.setIsVisible(0); // 👑 设置为禁用
            menuMapper.updateById(child);
            cascadeHideChildren(child.getMenuId());
        }
    }

    private SysMenu requireMenu(Long menuId) {
        SysMenu menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 2013, "菜单不存在");
        }
        return menu;
    }

    private List<MenuTreeVO> buildTree(List<SysMenu> menus) {
        Map<Long, MenuTreeVO> nodeMap = menus.stream()
            .collect(Collectors.toMap(SysMenu::getMenuId, menuConverter::toTreeNode));
        List<MenuTreeVO> roots = new ArrayList<>();
        for (SysMenu menu : menus) {
            MenuTreeVO node = nodeMap.get(menu.getMenuId());
            if (menu.getParentId() == null || menu.getParentId() == 0L) {
                roots.add(node);
            } else {
                MenuTreeVO parent = nodeMap.get(menu.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        return roots;
    }
}

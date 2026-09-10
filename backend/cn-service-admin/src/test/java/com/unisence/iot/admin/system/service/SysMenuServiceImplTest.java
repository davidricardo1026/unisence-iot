package com.unisence.iot.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.entity.SysMenu;
import com.unisence.iot.admin.mapper.SysMenuMapper;
import com.unisence.iot.admin.mapper.SysRoleMenuMapper;
import com.unisence.iot.admin.system.converter.SysMenuConverter;
import com.unisence.iot.admin.system.dto.MenuBatchVisibilityRequest;
import com.unisence.iot.admin.system.dto.MenuBatchVisibilityRequest.MenuVersionItem;
import com.unisence.iot.admin.system.dto.MenuCreateRequest;
import com.unisence.iot.admin.system.dto.MenuUpdateRequest;
import com.unisence.iot.admin.system.vo.MenuTreeVO;
import com.unisence.iot.common.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysMenuServiceImpl} 业务逻辑单元测试。
 * <p>
 * 手动 mock 全部 4 个协作方，纯 Mockito，不加载 Spring / MyBatis / DB。覆盖：
 * 树装配、可见性父子约束、禁用级联、批量可见性、{@code getSidebarMenuTree} 的
 * superAdmin / 普通用户两条分支（{@code StpUtil} 静态调用用 mockStatic 隔离），
 * 以及结构 CRUD 的层级校验、类型字段校验、唯一性校验与权限缓存失效。
 * <p>
 * {@code deleteMenu} 落库经 {@code BaseServiceImpl.removeById}（依赖 MyBatis 表元信息），
 * 纯单测无法覆盖，故只测其两道前置护栏；落库行为见 {@code LogicDeleteServiceIT}。
 */
class SysMenuServiceImplTest {

    private SysMenuMapper menuMapper;
    private SysMenuConverter menuConverter;
    private SysRoleMenuMapper roleMenuMapper;
    private SysUserCacheService userCacheService;
    private SysMenuServiceImpl service;

    @BeforeEach
    void setUp() {
        menuMapper = mock(SysMenuMapper.class);
        menuConverter = mock(SysMenuConverter.class);
        roleMenuMapper = mock(SysRoleMenuMapper.class);
        userCacheService = mock(SysUserCacheService.class);
        service = new SysMenuServiceImpl(menuMapper, menuConverter, roleMenuMapper, userCacheService);
    }

    // ---------- 树装配 ----------

    @Test
    @DisplayName("getMenuTree：扁平列表按 parentId 装配成树")
    void getMenuTree_buildsNestedTree() {
        // 1(根) - {2,3 子}; 4(根)
        when(menuMapper.selectList(any())).thenReturn(List.of(
            menu(1L, 0L), menu(2L, 1L), menu(3L, 1L), menu(4L, 0L)));
        stubToTreeNode();

        List<MenuTreeVO> tree = service.getMenuTree(null, null, null);

        assertThat(tree).extracting(MenuTreeVO::getMenuId).containsExactly(1L, 4L);
        assertThat(tree.get(0).getChildren()).extracting(MenuTreeVO::getMenuId).containsExactly(2L, 3L);
        assertThat(tree.get(1).getChildren()).isEmpty();
    }

    // ---------- updateVisibility ----------

    @Test
    @DisplayName("updateVisibility：菜单不存在 → 404/2013")
    void updateVisibility_notFound_notFound() {
        when(menuMapper.selectById(5L)).thenReturn(null);

        assertBusinessError(() -> service.updateVisibility(5L, 0, 1), HttpStatus.NOT_FOUND, 2013);
    }

    @Test
    @DisplayName("updateVisibility：上级不可见时启用子项 → 400/2011，不落库")
    void updateVisibility_enableWhileParentHidden_badRequest() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 1L));
        SysMenu parent = menu(1L, 0L);
        parent.setIsVisible(0);
        when(menuMapper.selectById(1L)).thenReturn(parent);

        assertBusinessError(() -> service.updateVisibility(5L, 1, 0), HttpStatus.BAD_REQUEST, 2011);

        verify(menuMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("updateVisibility：上级可见时启用子项 → 落库，且不触发级联")
    void updateVisibility_enableWithVisibleParent_updates() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 1L));
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L)); // parent isVisible=1

        service.updateVisibility(5L, 1, 0);

        verify(menuMapper).updateByIdWithVersionCheck(any(SysMenu.class));
        verify(menuMapper, never()).selectList(any()); // 启用不级联
    }

    @Test
    @DisplayName("updateVisibility：禁用节点 → 落库并级联禁用可见子节点")
    void updateVisibility_disable_cascadesToChildren() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 0L));
        // 级联第一层返回子 10，第二层无子
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(10L, 5L)), List.of());

        service.updateVisibility(5L, 0, 0);

        verify(menuMapper).updateByIdWithVersionCheck(any(SysMenu.class));
        ArgumentCaptor<SysMenu> childCaptor = ArgumentCaptor.forClass(SysMenu.class);
        verify(menuMapper).updateById(childCaptor.capture());
        assertThat(childCaptor.getValue().getMenuId()).isEqualTo(10L);
        assertThat(childCaptor.getValue().getIsVisible()).isEqualTo(0); // 子被级联禁用
    }

    // ---------- batchUpdateVisibility ----------

    @Test
    @DisplayName("batchUpdateVisibility：items 为空 → 直接返回，不落库")
    void batchUpdateVisibility_emptyItems_noop() {
        service.batchUpdateVisibility(List.of(), List.of());

        verify(menuMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("batchUpdateVisibility：按可见集合逐项设置 isVisible")
    void batchUpdateVisibility_setsVisibilityPerSet() {
        service.batchUpdateVisibility(List.of(10L), List.of(item(10L, 1), item(11L, 1)));

        ArgumentCaptor<SysMenu> captor = ArgumentCaptor.forClass(SysMenu.class);
        verify(menuMapper, times(2)).updateByIdWithVersionCheck(captor.capture());
        SysMenu m10 = captor.getAllValues().stream().filter(m -> m.getMenuId() == 10L).findFirst().orElseThrow();
        SysMenu m11 = captor.getAllValues().stream().filter(m -> m.getMenuId() == 11L).findFirst().orElseThrow();
        assertThat(m10.getIsVisible()).isEqualTo(1); // 在可见集合内
        assertThat(m11.getIsVisible()).isEqualTo(0); // 不在集合内
    }

    // ---------- getSidebarMenuTree ----------

    @Test
    @DisplayName("getSidebarMenuTree：superAdmin 拉取全部菜单")
    void getSidebarMenuTree_superAdmin_loadsAll() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            stp.when(() -> StpUtil.hasPermission("*")).thenReturn(true);
            when(menuMapper.selectList(any())).thenReturn(List.of(menu(1L, 0L), menu(2L, 1L)));
            stubToTreeNode();

            List<MenuTreeVO> tree = service.getSidebarMenuTree();

            assertThat(tree).hasSize(1);
            assertThat(tree.get(0).getChildren()).hasSize(1);
        }
        verify(menuMapper).selectList(any());
        verify(menuMapper, never()).selectAccessibleMenusByUserId(any());
    }

    @Test
    @DisplayName("getSidebarMenuTree：普通用户按权限拉取可访问菜单")
    void getSidebarMenuTree_normalUser_loadsAccessible() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(2L);
            stp.when(() -> StpUtil.hasPermission("*")).thenReturn(false);
            when(menuMapper.selectAccessibleMenusByUserId(2L)).thenReturn(List.of(menu(1L, 0L)));
            stubToTreeNode();

            List<MenuTreeVO> tree = service.getSidebarMenuTree();

            assertThat(tree).hasSize(1);
        }
        verify(menuMapper).selectAccessibleMenusByUserId(2L);
        verify(menuMapper, never()).selectList(any());
    }

    // ---------- createMenu ----------

    @Test
    @DisplayName("createMenu：模块挂顶级 → 落库、返回自增主键、失效权限缓存")
    void createMenu_moduleAtRoot_insertsAndEvicts() {
        stubToEntity();
        when(menuMapper.insert(any(SysMenu.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysMenu.class).setMenuId(100L); // 模拟自增主键回填
            return 1;
        });

        Long menuId = service.createMenu(createRequest(0L, "设备管理", "D", "/device", null, null));

        assertThat(menuId).isEqualTo(100L);
        verify(menuMapper).insert(any(SysMenu.class));
        verify(userCacheService).evictAllPerms();
    }

    @Test
    @DisplayName("createMenu：按钮挂菜单下 → 通过层级与字段校验")
    void createMenu_buttonUnderMenu_ok() {
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "C"));
        stubToEntity();

        service.createMenu(createRequest(1L, "新增设备", "F", null, null, "iot:device:add"));

        verify(menuMapper).insert(any(SysMenu.class));
    }

    @Test
    @DisplayName("createMenu：按钮挂顶级 → 400/2029，不落库")
    void createMenu_buttonAtRoot_badRequest() {
        assertBusinessError(() -> service.createMenu(createRequest(0L, "新增", "F", null, null, "iot:x:add")),
                            HttpStatus.BAD_REQUEST, 2029);

        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    @DisplayName("createMenu：模块挂到父节点下 → 400/2029")
    void createMenu_moduleWithParent_badRequest() {
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "D"));

        assertBusinessError(() -> service.createMenu(createRequest(1L, "设备管理", "D", "/device", null, null)),
                            HttpStatus.BAD_REQUEST, 2029);
    }

    @Test
    @DisplayName("createMenu：目录挂菜单下 → 400/2029（M 只能挂 D）")
    void createMenu_directoryUnderMenu_badRequest() {
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "C"));

        assertBusinessError(() -> service.createMenu(createRequest(1L, "日志管理", "M", "/log", null, null)),
                            HttpStatus.BAD_REQUEST, 2029);
    }

    @Test
    @DisplayName("createMenu：父菜单不存在 → 400/2010")
    void createMenu_parentMissing_badRequest() {
        when(menuMapper.selectById(9L)).thenReturn(null);

        assertBusinessError(() -> service.createMenu(createRequest(9L, "设备台账", "C", "/device/list",
                                                                   "device/list/index", null)),
                            HttpStatus.BAD_REQUEST, 2010);
    }

    @Test
    @DisplayName("createMenu：菜单缺 component → 400/2031")
    void createMenu_menuWithoutComponent_badRequest() {
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "D"));

        assertBusinessError(() -> service.createMenu(createRequest(1L, "设备台账", "C", "/device/list", null, null)),
                            HttpStatus.BAD_REQUEST, 2031);
    }

    @Test
    @DisplayName("createMenu：按钮填了 path → 400/2031")
    void createMenu_buttonWithPath_badRequest() {
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "C"));

        assertBusinessError(() -> service.createMenu(createRequest(1L, "新增", "F", "/x", null, "iot:x:add")),
                            HttpStatus.BAD_REQUEST, 2031);
    }

    @Test
    @DisplayName("createMenu：模块填了 perms → 400/2031")
    void createMenu_moduleWithPerms_badRequest() {
        assertBusinessError(() -> service.createMenu(createRequest(0L, "设备管理", "D", "/device", null, "iot:x")),
                            HttpStatus.BAD_REQUEST, 2031);
    }

    @Test
    @DisplayName("createMenu：权限标识重复 → 409/2009")
    void createMenu_duplicatePerms_conflict() {
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "C"));
        when(menuMapper.selectCount(any())).thenReturn(1L);

        assertBusinessError(() -> service.createMenu(createRequest(1L, "新增", "F", null, null, "iot:x:add")),
                            HttpStatus.CONFLICT, 2009);

        verify(menuMapper, never()).insert(any(SysMenu.class));
    }

    @Test
    @DisplayName("createMenu：同级下名称重复 → 409/2030")
    void createMenu_duplicateNameUnderParent_conflict() {
        when(menuMapper.selectCount(any())).thenReturn(1L); // perms 为空，直接落到名称唯一性校验

        assertBusinessError(() -> service.createMenu(createRequest(0L, "系统管理", "D", "/system", null, null)),
                            HttpStatus.CONFLICT, 2030);
    }

    @Test
    @DisplayName("createMenu：父节点不可见 → 新节点强制不可见")
    void createMenu_parentHidden_forcesInvisible() {
        SysMenu parent = menu(1L, 0L, "D");
        parent.setIsVisible(0);
        when(menuMapper.selectById(1L)).thenReturn(parent);
        stubToEntity();

        service.createMenu(createRequest(1L, "设备台账", "C", "/device/list", "device/list/index", null));

        ArgumentCaptor<SysMenu> captor = ArgumentCaptor.forClass(SysMenu.class);
        verify(menuMapper).insert(captor.capture());
        assertThat(captor.getValue().getIsVisible()).isEqualTo(0);
    }

    // ---------- updateMenu ----------

    @Test
    @DisplayName("updateMenu：菜单不存在 → 404/2013")
    void updateMenu_notFound_notFound() {
        when(menuMapper.selectById(5L)).thenReturn(null);

        assertBusinessError(() -> service.updateMenu(5L, updateRequest(0L, "设备管理", "D", "/device", null, null, 1)),
                            HttpStatus.NOT_FOUND, 2013);
    }

    @Test
    @DisplayName("updateMenu：父级指向自身 → 400/2028")
    void updateMenu_parentIsSelf_badRequest() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 1L, "C"));

        assertBusinessError(() -> service.updateMenu(5L, updateRequest(5L, "设备台账", "C", "/d", "d/index", null, 1)),
                            HttpStatus.BAD_REQUEST, 2028);

        verify(menuMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("updateMenu：父级指向自身的子节点 → 400/2028")
    void updateMenu_parentIsDescendant_badRequest() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 1L, "C"));
        when(menuMapper.selectById(10L)).thenReturn(menu(10L, 5L, "M")); // 10 是 5 的子节点

        assertBusinessError(() -> service.updateMenu(5L, updateRequest(10L, "设备台账", "C", "/d", "d/index", null, 1)),
                            HttpStatus.BAD_REQUEST, 2028);

        verify(menuMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("updateMenu：父级不可见时置为可见 → 400/2011")
    void updateMenu_enableWhileParentHidden_badRequest() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 1L, "C"));
        SysMenu parent = menu(1L, 0L, "D");
        parent.setIsVisible(0);
        when(menuMapper.selectById(1L)).thenReturn(parent);

        MenuUpdateRequest request = updateRequest(1L, "设备台账", "C", "/d", "d/index", null, 1);
        request.setIsVisible(1);

        assertBusinessError(() -> service.updateMenu(5L, request), HttpStatus.BAD_REQUEST, 2011);
    }

    @Test
    @DisplayName("updateMenu：正常修改 → 写入乐观锁版本、落库、失效权限缓存")
    void updateMenu_valid_updatesWithVersionAndEvicts() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 1L, "C"));
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "D"));

        MenuUpdateRequest request = updateRequest(1L, "设备台账", "C", "/d", "d/index", null, 7);
        request.setIsVisible(1);

        service.updateMenu(5L, request);

        ArgumentCaptor<SysMenu> captor = ArgumentCaptor.forClass(SysMenu.class);
        verify(menuMapper).updateByIdWithVersionCheck(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(7);
        verify(menuConverter).updateEntity(any(SysMenu.class), eq(request));
        verify(userCacheService).evictAllPerms();
    }

    @Test
    @DisplayName("updateMenu：改为禁用 → 级联禁用子树")
    void updateMenu_disable_cascadesToChildren() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 1L, "C"));
        when(menuMapper.selectById(1L)).thenReturn(menu(1L, 0L, "D"));
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(10L, 5L, "F")), List.of());

        MenuUpdateRequest request = updateRequest(1L, "设备台账", "C", "/d", "d/index", null, 1);
        request.setIsVisible(0);

        service.updateMenu(5L, request);

        ArgumentCaptor<SysMenu> childCaptor = ArgumentCaptor.forClass(SysMenu.class);
        verify(menuMapper).updateById(childCaptor.capture());
        assertThat(childCaptor.getValue().getMenuId()).isEqualTo(10L);
        assertThat(childCaptor.getValue().getIsVisible()).isEqualTo(0);
    }

    // ---------- deleteMenu（仅护栏）----------

    @Test
    @DisplayName("deleteMenu：菜单不存在 → 404/2013")
    void deleteMenu_notFound_notFound() {
        when(menuMapper.selectById(9L)).thenReturn(null);

        assertBusinessError(() -> service.deleteMenu(9L), HttpStatus.NOT_FOUND, 2013);
    }

    @Test
    @DisplayName("deleteMenu：存在子菜单 → 409/2012，不检查角色绑定")
    void deleteMenu_hasChildren_conflict() {
        when(menuMapper.selectById(9L)).thenReturn(menu(9L, 0L, "D"));
        when(menuMapper.selectCount(any())).thenReturn(2L);

        assertBusinessError(() -> service.deleteMenu(9L), HttpStatus.CONFLICT, 2012);

        verify(roleMenuMapper, never()).selectCount(any());
        verify(userCacheService, never()).evictAllPerms();
    }

    @Test
    @DisplayName("deleteMenu：仍被角色绑定 → 409/2027")
    void deleteMenu_boundToRole_conflict() {
        when(menuMapper.selectById(9L)).thenReturn(menu(9L, 1L, "F"));
        when(menuMapper.selectCount(any())).thenReturn(0L);
        when(roleMenuMapper.selectCount(any())).thenReturn(3L);

        assertBusinessError(() -> service.deleteMenu(9L), HttpStatus.CONFLICT, 2027);

        verify(userCacheService, never()).evictAllPerms();
    }

    // ---------- 权限缓存失效（可见性路径）----------

    @Test
    @DisplayName("updateVisibility：可见性变更后必须失效权限点缓存")
    void updateVisibility_evictsPermsCache() {
        when(menuMapper.selectById(5L)).thenReturn(menu(5L, 0L));

        service.updateVisibility(5L, 0, 0);

        verify(userCacheService).evictAllPerms();
    }

    @Test
    @DisplayName("batchUpdateVisibility：批量变更后必须失效权限点缓存")
    void batchUpdateVisibility_evictsPermsCache() {
        service.batchUpdateVisibility(List.of(10L), List.of(item(10L, 1)));

        verify(userCacheService).evictAllPerms();
    }

    // ---------- helpers ----------

    /**
     * 模拟 MapStruct：把请求字段搬到实体上
     */
    private void stubToEntity() {
        when(menuConverter.toEntity(any())).thenAnswer(inv -> {
            MenuCreateRequest r = inv.getArgument(0);
            SysMenu m = new SysMenu();
            m.setParentId(r.getParentId());
            m.setMenuName(r.getMenuName());
            m.setMenuType(r.getMenuType());
            m.setPath(r.getPath());
            m.setComponent(r.getComponent());
            m.setPerms(r.getPerms());
            m.setSortOrder(r.getSortOrder());
            m.setIsVisible(r.getIsVisible());
            return m;
        });
    }

    private static MenuCreateRequest createRequest(Long parentId, String menuName, String menuType,
                                                   String path, String component, String perms) {
        MenuCreateRequest r = new MenuCreateRequest();
        r.setParentId(parentId);
        r.setMenuName(menuName);
        r.setMenuType(menuType);
        r.setPath(path);
        r.setComponent(component);
        r.setPerms(perms);
        return r;
    }

    private static MenuUpdateRequest updateRequest(Long parentId, String menuName, String menuType,
                                                   String path, String component, String perms, Integer version) {
        MenuUpdateRequest r = new MenuUpdateRequest();
        r.setParentId(parentId);
        r.setMenuName(menuName);
        r.setMenuType(menuType);
        r.setPath(path);
        r.setComponent(component);
        r.setPerms(perms);
        r.setVersion(version);
        return r;
    }

    private static SysMenu menu(Long menuId, Long parentId, String menuType) {
        SysMenu m = menu(menuId, parentId);
        m.setMenuType(menuType);
        return m;
    }

    private void stubToTreeNode() {
        when(menuConverter.toTreeNode(any())).thenAnswer(inv -> {
            SysMenu m = inv.getArgument(0);
            MenuTreeVO vo = new MenuTreeVO();
            vo.setMenuId(m.getMenuId());
            vo.setParentId(m.getParentId());
            return vo;
        });
    }

    private static SysMenu menu(Long menuId, Long parentId) {
        SysMenu m = new SysMenu();
        m.setMenuId(menuId);
        m.setParentId(parentId);
        m.setIsVisible(1);
        return m;
    }

    private static MenuVersionItem item(Long menuId, Integer version) {
        MenuVersionItem it = new MenuBatchVisibilityRequest.MenuVersionItem();
        it.setMenuId(menuId);
        it.setVersion(version);
        return it;
    }

    private static void assertBusinessError(ThrowingCallable call, HttpStatus status, int code) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(status);
                assertThat(ex.getCode()).isEqualTo(code);
            });
    }
}

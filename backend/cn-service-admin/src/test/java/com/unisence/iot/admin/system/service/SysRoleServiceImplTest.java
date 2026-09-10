package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.cache.CacheEvictService;
import com.unisence.iot.admin.entity.SysRole;
import com.unisence.iot.admin.entity.SysRoleMenu;
import com.unisence.iot.admin.mapper.SysRoleMapper;
import com.unisence.iot.admin.mapper.SysRoleMenuMapper;
import com.unisence.iot.admin.mapper.SysUserRoleMapper;
import com.unisence.iot.admin.system.converter.SysRoleConverter;
import com.unisence.iot.admin.system.dto.RoleCreateRequest;
import com.unisence.iot.admin.system.dto.RoleUpdateRequest;
import com.unisence.iot.admin.system.vo.MenuTreeVO;
import com.unisence.iot.admin.system.vo.RoleVO;
import com.unisence.iot.common.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysRoleServiceImpl} 业务逻辑单元测试（写操作 + 读取）。
 * <p>
 * 手动 mock 全部 6 个协作方，不加载 Spring / MyBatis / DB。
 * 覆盖 superAdmin 三道护栏、角色编码唯一性、删除前用户占用检查、菜单重绑、缓存失效等业务规则。
 * <p>
 * {@code deleteRole} 落库经 {@code BaseServiceImpl.removeById}（依赖 MyBatis 表元信息），
 * 纯单测无法覆盖，故只测其三道前置护栏。
 */
class SysRoleServiceImplTest {

    private SysRoleMapper roleMapper;
    private SysRoleMenuMapper roleMenuMapper;
    private SysUserRoleMapper userRoleMapper;
    private CacheEvictService cacheEvictService;
    private SysUserCacheService userCacheService;
    private SysRoleConverter roleConverter;
    private SysMenuService menuService;

    private SysRoleServiceImpl service;

    @BeforeEach
    void setUp() {
        roleMapper = mock(SysRoleMapper.class);
        roleMenuMapper = mock(SysRoleMenuMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        cacheEvictService = mock(CacheEvictService.class);
        userCacheService = mock(SysUserCacheService.class);
        roleConverter = mock(SysRoleConverter.class);
        menuService = mock(SysMenuService.class);

        service = new SysRoleServiceImpl(roleMapper, roleMenuMapper, userRoleMapper,
                                         cacheEvictService, userCacheService, roleConverter, menuService);
    }

    // ---------- getRole ----------

    @Test
    @DisplayName("getFormOptions：由角色域返回菜单树")
    void getFormOptions_returnsMenus() {
        MenuTreeVO menu = new MenuTreeVO();
        menu.setMenuId(1L);
        when(menuService.getMenuTree(null, null, null)).thenReturn(List.of(menu));

        assertThat(service.getFormOptions().getMenus()).containsExactly(menu);
        verify(menuService).getMenuTree(null, null, null);
    }

    @Test
    @DisplayName("getRole：角色不存在 → 404/2008")
    void getRole_notFound_notFound() {
        when(roleMapper.selectById(5L)).thenReturn(null);

        assertBusinessError(() -> service.getRole(5L), HttpStatus.NOT_FOUND, 2008);
    }

    @Test
    @DisplayName("getRole：返回角色并回填 menuIds")
    void getRole_success_fillsMenuIds() {
        SysRole role = role(5L, "ops");
        when(roleMapper.selectById(5L)).thenReturn(role);
        RoleVO baseVo = new RoleVO();
        baseVo.setRoleId(5L);
        baseVo.setRoleCode("ops");
        when(roleConverter.toVO(role)).thenReturn(baseVo);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(10L), roleMenu(11L)));

        RoleVO vo = service.getRole(5L);

        assertThat(vo.getRoleId()).isEqualTo(5L);
        assertThat(vo.getMenuIds()).containsExactly(10L, 11L);
    }

    // ---------- createRole ----------

    @Test
    @DisplayName("createRole：roleCode=superAdmin → 403/2014，不查重不插入")
    void createRole_superAdmin_forbidden() {
        assertBusinessError(() -> service.createRole(createRequest("superAdmin")),
                            HttpStatus.FORBIDDEN, 2014);

        verify(roleMapper, never()).selectCount(any());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    @DisplayName("createRole：角色编码已存在 → 409/2007，不插入")
    void createRole_duplicateCode_conflict() {
        when(roleMapper.selectCount(any())).thenReturn(1L);

        assertBusinessError(() -> service.createRole(createRequest("ops")),
                            HttpStatus.CONFLICT, 2007);

        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    @DisplayName("createRole：成功 → 插入角色、绑定菜单、返回自增 id")
    void createRole_success_persistsRoleAndMenus() {
        when(roleMapper.selectCount(any())).thenReturn(0L);
        SysRole entity = new SysRole();
        entity.setRoleCode("ops");
        when(roleConverter.toEntity(any())).thenReturn(entity);
        when(roleMapper.insert(any(SysRole.class))).thenAnswer(inv -> {
            ((SysRole) inv.getArgument(0)).setRoleId(50L);
            return 1;
        });

        RoleCreateRequest req = createRequest("ops");
        req.setMenuIds(List.of(1L, 2L));

        Long id = service.createRole(req);

        assertThat(id).isEqualTo(50L);
        verify(roleMenuMapper).delete(any());                     // bindMenus 先清旧绑定
        verify(roleMenuMapper, times(2)).insert(any(SysRoleMenu.class)); // 两个菜单各一条
    }

    // ---------- updateRole ----------

    @Test
    @DisplayName("updateRole：角色不存在 → 404/2008")
    void updateRole_notFound_notFound() {
        when(roleMapper.selectById(7L)).thenReturn(null);

        assertBusinessError(() -> service.updateRole(7L, updateRequest("ops")),
                            HttpStatus.NOT_FOUND, 2008);
    }

    @Test
    @DisplayName("updateRole：目标是 superAdmin → 403/2015，不落库")
    void updateRole_superAdmin_forbidden() {
        when(roleMapper.selectById(7L)).thenReturn(role(7L, "superAdmin"));

        assertBusinessError(() -> service.updateRole(7L, updateRequest("ops")),
                            HttpStatus.FORBIDDEN, 2015);

        verify(roleMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("updateRole：成功 → 带乐观锁落库、重绑菜单、失效角色与权限缓存")
    void updateRole_success_updatesAndRebindsMenus() {
        SysRole role = role(7L, "ops");
        when(roleMapper.selectById(7L)).thenReturn(role);
        when(roleMapper.selectCount(any())).thenReturn(0L);

        RoleUpdateRequest req = updateRequest("ops");
        req.setVersion(3);
        req.setMenuIds(List.of(1L));

        service.updateRole(7L, req);

        assertThat(role.getVersion()).isEqualTo(3);
        verify(roleConverter).updateEntity(role, req);
        verify(roleMapper).updateByIdWithVersionCheck(role);
        verify(roleMenuMapper).delete(any());
        verify(roleMenuMapper).insert(any(SysRoleMenu.class));
        verify(userCacheService).evictAllPerms();
    }

    // ---------- deleteRole（仅护栏）----------

    @Test
    @DisplayName("deleteRole：角色不存在 → 404/2008")
    void deleteRole_notFound_notFound() {
        when(roleMapper.selectById(9L)).thenReturn(null);

        assertBusinessError(() -> service.deleteRole(9L), HttpStatus.NOT_FOUND, 2008);
    }

    @Test
    @DisplayName("deleteRole：目标是 superAdmin → 403/2016，不检查占用")
    void deleteRole_superAdmin_forbidden() {
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "superAdmin"));

        assertBusinessError(() -> service.deleteRole(9L), HttpStatus.FORBIDDEN, 2016);

        verify(userRoleMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("deleteRole：角色已被用户绑定 → 409/2006，不删除")
    void deleteRole_boundToUsers_conflict() {
        when(roleMapper.selectById(9L)).thenReturn(role(9L, "ops"));
        when(userRoleMapper.selectCount(any())).thenReturn(2L);

        assertBusinessError(() -> service.deleteRole(9L), HttpStatus.CONFLICT, 2006);

        verify(roleMenuMapper, never()).delete(any());
    }

    // ---------- helpers ----------

    private static SysRole role(Long roleId, String roleCode) {
        SysRole r = new SysRole();
        r.setRoleId(roleId);
        r.setRoleCode(roleCode);
        r.setRoleName("运维");
        r.setStatus(1);
        return r;
    }

    private static SysRoleMenu roleMenu(Long menuId) {
        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(5L);
        rm.setMenuId(menuId);
        return rm;
    }

    private static RoleCreateRequest createRequest(String roleCode) {
        RoleCreateRequest r = new RoleCreateRequest();
        r.setRoleName("运维");
        r.setRoleCode(roleCode);
        return r;
    }

    private static RoleUpdateRequest updateRequest(String roleCode) {
        RoleUpdateRequest r = new RoleUpdateRequest();
        r.setRoleName("运维");
        r.setRoleCode(roleCode);
        r.setStatus(1);
        r.setVersion(0);
        return r;
    }

    private static void assertBusinessError(ThrowingCallable call, HttpStatus status, int code) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(status);
                assertThat(ex.getCode()).isEqualTo(code);
            });
    }
}

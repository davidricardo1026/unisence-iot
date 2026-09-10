package com.unisence.iot.admin.mapper;

import com.unisence.iot.admin.entity.*;
import com.unisence.iot.admin.support.AbstractMysqlIntegrationTest;
import com.unisence.iot.admin.support.MapperItConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 权限解析集成测试（Testcontainers 真 MySQL）—— 覆盖 {@link SysMenuMapper} / {@link SysRoleMapper} 的自定义 SQL。
 * <p>
 * 这三条查询是**纯单元测试完全碰不到**的：多表 join + 递归 CTE + {@code deleted/status/is_visible} 过滤，
 * SQL 极易写错。用一张小 RBAC 图（用户→角色→菜单，含停用角色 / 隐藏按钮 / 目录）精确验证：
 * <ul>
 *   <li>{@code selectRoleCodesByUserId} —— 只返回 {@code status=1} 的角色码（停用角色被过滤）。</li>
 *   <li>{@code selectPermsByUserId} —— 直接授权的可见菜单的 perms；排除 null perms（目录）、{@code is_visible=0}、停用角色的菜单。</li>
 *   <li>{@code selectAccessibleMenusByUserId} —— 递归 CTE 从授权叶子**向上补全父级目录**；排除隐藏 / 未授权。</li>
 * </ul>
 */
@SpringBootTest(classes = MapperItConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RbacPermissionQueryIT extends AbstractMysqlIntegrationTest {

    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private SysMenuMapper menuMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private SysRoleMenuMapper roleMenuMapper;

    private Long userId;
    private Long dirId;        // 目录 M（perms=null；靠递归补全）
    private Long menuCId;      // 菜单 C（sys:user:list）
    private Long btnDeleteId;  // 按钮 F（sys:user:delete，可见）
    private Long btnHiddenId;  // 按钮 F（sys:user:add，is_visible=0）
    private Long secretMenuId; // 停用角色 R2 独占的菜单（应处处被过滤）

    @BeforeEach
    void seedRbacGraph() {
        SysUser u = newUser("rbac_u", "U");
        userMapper.insert(u);
        userId = u.getUserId();

        SysRole active = newRole("ROLE_OP", "操作员", 1);
        SysRole disabled = newRole("ROLE_OFF", "停用角色", 0);
        roleMapper.insert(active);
        roleMapper.insert(disabled);

        SysMenu dir = newMenu(0L, "M", "系统管理", null, 1, 1);
        menuMapper.insert(dir);
        dirId = dir.getMenuId();
        SysMenu menuC = newMenu(dirId, "C", "用户管理", "sys:user:list", 1, 1);
        menuMapper.insert(menuC);
        menuCId = menuC.getMenuId();
        SysMenu btnDelete = newMenu(menuCId, "F", "删除", "sys:user:delete", 1, 1);
        menuMapper.insert(btnDelete);
        btnDeleteId = btnDelete.getMenuId();
        SysMenu btnHidden = newMenu(menuCId, "F", "新增", "sys:user:add", 0, 2); // 隐藏
        menuMapper.insert(btnHidden);
        btnHiddenId = btnHidden.getMenuId();
        SysMenu secret = newMenu(dirId, "C", "机密", "sys:secret:list", 1, 3);
        menuMapper.insert(secret);
        secretMenuId = secret.getMenuId();

        // 用户挂两个角色：一个正常、一个停用
        userRoleMapper.insert(userRole(userId, active.getRoleId()));
        userRoleMapper.insert(userRole(userId, disabled.getRoleId()));
        // 正常角色授权：菜单C + 删除按钮 + 隐藏按钮（故意不直接授权目录，留给递归补全）
        roleMenuMapper.insert(roleMenu(active.getRoleId(), menuCId));
        roleMenuMapper.insert(roleMenu(active.getRoleId(), btnDeleteId));
        roleMenuMapper.insert(roleMenu(active.getRoleId(), btnHiddenId));
        // 停用角色授权机密菜单（应被 status=1 过滤掉）
        roleMenuMapper.insert(roleMenu(disabled.getRoleId(), secretMenuId));
    }

    @Test
    @DisplayName("selectRoleCodesByUserId：只返回 status=1 的角色码，停用角色被过滤")
    void selectRoleCodes_excludesDisabledRole() {
        List<String> codes = roleMapper.selectRoleCodesByUserId(userId);
        assertThat(codes).containsExactly("ROLE_OP");
    }

    @Test
    @DisplayName("selectPermsByUserId：直接授权的可见菜单 perms；排除 null / is_visible=0 / 停用角色")
    void selectPerms_filtersInvisibleNullAndDisabledRole() {
        List<String> perms = menuMapper.selectPermsByUserId(userId);
        assertThat(perms).containsExactlyInAnyOrder("sys:user:list", "sys:user:delete");
        // 隐藏按钮(sys:user:add)、目录(perms=null)、停用角色的机密菜单(sys:secret:list) 都不应出现
        assertThat(perms).doesNotContain("sys:user:add", "sys:secret:list");
    }

    @Test
    @DisplayName("selectAccessibleMenusByUserId：递归 CTE 从授权叶子向上补全父级目录")
    void selectAccessibleMenus_recursivelyIncludesParents() {
        List<Long> ids = menuMapper.selectAccessibleMenusByUserId(userId)
            .stream().map(SysMenu::getMenuId).toList();

        // 授权叶子 {menuC, btnDelete} + 递归向上补全的目录 dir；隐藏按钮与未授权/停用角色菜单不含
        assertThat(ids).containsExactlyInAnyOrder(dirId, menuCId, btnDeleteId);
        assertThat(ids).doesNotContain(btnHiddenId, secretMenuId);
    }

    // ---- builders ----

    private static SysUser newUser(String code, String name) {
        SysUser u = new SysUser();
        u.setUserCode(code);
        u.setUserName(name);
        u.setStatus(1);
        u.setDeptId(1L);
        return u;
    }

    private static SysRole newRole(String code, String name, int status) {
        SysRole r = new SysRole();
        r.setRoleCode(code);
        r.setRoleName(name);
        r.setStatus(status);
        return r;
    }

    private static SysMenu newMenu(Long parentId, String type, String name, String perms, int visible, int sort) {
        SysMenu m = new SysMenu();
        m.setParentId(parentId);
        m.setMenuType(type);
        m.setMenuName(name);
        m.setPerms(perms);
        m.setIsVisible(visible);
        m.setSortOrder(sort);
        return m;
    }

    private static SysUserRole userRole(Long userId, Long roleId) {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        return ur;
    }

    private static SysRoleMenu roleMenu(Long roleId, Long menuId) {
        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(roleId);
        rm.setMenuId(menuId);
        return rm;
    }
}

package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.cache.CacheEvictService;
import com.unisence.iot.admin.entity.SysAuthIdentity;
import com.unisence.iot.admin.entity.SysRole;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.entity.SysUserRole;
import com.unisence.iot.admin.mapper.*;
import com.unisence.iot.admin.mapper.projection.UserRoleAssignmentRow;
import com.unisence.iot.admin.system.converter.SysRoleConverter;
import com.unisence.iot.admin.system.converter.SysUserConverter;
import com.unisence.iot.admin.system.dto.ResetPasswordRequest;
import com.unisence.iot.admin.system.dto.UserCreateRequest;
import com.unisence.iot.admin.system.dto.UserUpdateRequest;
import com.unisence.iot.admin.system.vo.DeptTreeVO;
import com.unisence.iot.admin.system.vo.RoleVO;
import com.unisence.iot.admin.system.vo.UserVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysUserServiceImpl} 业务逻辑单元测试(写操作)。
 * <p>
 * 手动 mock 全部 13 个协作方(宽松打桩),不加载 Spring / MyBatis / DB。
 * 覆盖 superAdmin 三道护栏、用户编码唯一性、存在性校验、密码编码、角色重绑等业务规则。
 * <p>
 * {@code deleteUser} 的落库路径经 {@code BaseServiceImpl.removeById} → {@code TableInfoHelper}
 * 逻辑删除,依赖 MyBatis 表元信息注册,纯单测无法覆盖,故只测其两道前置护栏,
 * 落库行为留给 DB 集成测试。
 */
class SysUserServiceImplTest {

    private SysUserMapper userMapper;
    private SysAuthIdentityMapper authIdentityMapper;
    private SysAuthIdentityService authIdentityService;
    private SysUserRoleMapper userRoleMapper;
    private SysDeptService deptService;
    private PasswordEncoder passwordEncoder;
    private CacheEvictService cacheEvictService;
    private SysUserCacheService userCacheService;
    private SysUserConverter userConverter;
    private SysRoleConverter roleConverter;
    private SysMenuMapper menuMapper;
    private SysRoleMapper roleMapper;
    private SysMenuService menuService;

    private SysUserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        authIdentityMapper = mock(SysAuthIdentityMapper.class);
        authIdentityService = mock(SysAuthIdentityService.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        deptService = mock(SysDeptService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        cacheEvictService = mock(CacheEvictService.class);
        userCacheService = mock(SysUserCacheService.class);
        userConverter = mock(SysUserConverter.class);
        roleConverter = mock(SysRoleConverter.class);
        menuMapper = mock(SysMenuMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        menuService = mock(SysMenuService.class);

        service = new SysUserServiceImpl(userMapper,
                                         authIdentityMapper,
                                         authIdentityService,
                                         userRoleMapper,
                                         deptService,
                                         passwordEncoder,
                                         cacheEvictService,
                                         userCacheService,
                                         userConverter,
                                         roleConverter,
                                         menuMapper,
                                         roleMapper,
                                         menuService);
    }

    @Test
    @DisplayName("getFormOptions：由用户域返回部门与可分配角色")
    void getFormOptions_returnsDepartmentsAndRoles() {
        DeptTreeVO dept = new DeptTreeVO();
        dept.setDeptId(10L);
        SysRole role = new SysRole();
        role.setRoleId(3L);
        role.setRoleName("运维");
        when(deptService.getDeptTree(null, null)).thenReturn(List.of(dept));
        when(roleMapper.selectList(any())).thenReturn(List.of(role));
        RoleVO roleVo = new RoleVO();
        roleVo.setRoleId(3L);
        roleVo.setRoleName("运维");
        when(roleConverter.toVO(role)).thenReturn(roleVo);

        var result = service.getFormOptions();

        assertThat(result.getDepartments()).containsExactly(dept);
        assertThat(result.getRoles()).extracting("roleName").containsExactly("运维");
    }

    @Test
    @DisplayName("pageUsers：批量回填角色 ID 与名称，不依赖角色列表接口")
    void pageUsers_populatesRoleAssignmentsInBatch() {
        SysUser alice = user(7L, "alice");
        UserVO aliceVo = new UserVO();
        aliceVo.setUserId(7L);
        Page<SysUser> page = new Page<>(1, 20);
        page.setRecords(List.of(alice));
        page.setTotal(1);
        when(userMapper.selectPage(any(), any())).thenReturn(page);
        when(userConverter.toVO(alice)).thenReturn(aliceVo);

        UserRoleAssignmentRow assignment = new UserRoleAssignmentRow();
        assignment.setUserId(7L);
        assignment.setRoleId(3L);
        assignment.setRoleName("运维人员");
        when(roleMapper.selectRoleAssignmentsByUserIds(List.of(7L))).thenReturn(List.of(assignment));

        PageResult<UserVO> result = service.pageUsers(new PageRequest<>(1, 20, null));

        assertThat(result.list()).singleElement().satisfies(vo -> {
            assertThat(vo.getRoleIds()).containsExactly(3L);
            assertThat(vo.getRoleNames()).containsExactly("运维人员");
        });
        verify(roleMapper).selectRoleAssignmentsByUserIds(List.of(7L));
    }

    // ---------- createUser ----------

    @Test
    @DisplayName("createUser：userCode=superAdmin → 403/2019,不查重不插入")
    void createUser_superAdmin_forbidden() {
        assertBusinessError(() -> service.createUser(createRequest("superAdmin")),
                            HttpStatus.FORBIDDEN, 2019);

        verify(userMapper, never()).selectCount(any());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("createUser：用户编码已存在 → 409/2001,不插入")
    void createUser_duplicateCode_conflict() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertBusinessError(() -> service.createUser(createRequest("newuser")),
                            HttpStatus.CONFLICT, 2001);

        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("createUser：成功 → 插入用户、写 BCrypt 凭证、绑定角色、返回自增 id")
    void createUser_success_persistsUserIdentityAndRoles() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        SysUser entity = new SysUser();
        entity.setUserCode("newuser");
        when(userConverter.toEntity(any())).thenReturn(entity);
        // 模拟 MyBatis-Plus insert 回填自增主键
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setUserId(100L);
            return 1;
        });
        when(passwordEncoder.encode("deadbeef")).thenReturn("ENC");

        UserCreateRequest req = createRequest("newuser");
        req.setRoleIds(List.of(5L, 6L));

        Long id = service.createUser(req);

        assertThat(id).isEqualTo(100L);
        // 凭证:type=local、identifier=userCode、credential=编码后
        ArgumentCaptor<SysAuthIdentity> idc = ArgumentCaptor.forClass(SysAuthIdentity.class);
        verify(authIdentityMapper).insert(idc.capture());
        SysAuthIdentity savedIdentity = idc.getValue();
        assertThat(savedIdentity.getUserId()).isEqualTo(100L);
        assertThat(savedIdentity.getIdentityType()).isEqualTo("local");
        assertThat(savedIdentity.getIdentifier()).isEqualTo("newuser");
        assertThat(savedIdentity.getCredential()).isEqualTo("ENC");
        // 两个角色各插一条中间表
        verify(userRoleMapper, times(2)).insert(any(SysUserRole.class));
    }

    // ---------- updateUser ----------

    @Test
    @DisplayName("updateUser：用户不存在 → 404/2004")
    void updateUser_notFound_notFound() {
        when(userCacheService.getById(7L)).thenReturn(null);

        assertBusinessError(() -> service.updateUser(7L, updateRequest()),
                            HttpStatus.NOT_FOUND, 2004);
    }

    @Test
    @DisplayName("updateUser：目标是 superAdmin → 403/2017,不落库")
    void updateUser_superAdmin_forbidden() {
        when(userCacheService.getById(7L)).thenReturn(user(7L, "superAdmin"));

        assertBusinessError(() -> service.updateUser(7L, updateRequest()),
                            HttpStatus.FORBIDDEN, 2017);

        verify(userMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("updateUser：成功 → 带乐观锁落库、清旧角色重绑、失效缓存")
    void updateUser_success_updatesAndRebindsRoles() {
        SysUser existing = user(7L, "alice");
        when(userCacheService.getById(7L)).thenReturn(existing);

        UserUpdateRequest req = updateRequest();
        req.setVersion(3);
        req.setRoleIds(List.of(5L));

        service.updateUser(7L, req);

        assertThat(existing.getVersion()).isEqualTo(3);
        verify(userConverter).updateEntity(existing, req);
        verify(userMapper).updateByIdWithVersionCheck(existing);
        verify(userRoleMapper).delete(any());                    // 先清旧角色
        verify(userRoleMapper).insert(any(SysUserRole.class));   // 再绑新角色
        verify(userCacheService).evict(7L);
    }

    // ---------- deleteUser（仅护栏）----------

    @Test
    @DisplayName("deleteUser：用户不存在 → 404/2004")
    void deleteUser_notFound_notFound() {
        when(userCacheService.getById(9L)).thenReturn(null);

        assertBusinessError(() -> service.deleteUser(9L), HttpStatus.NOT_FOUND, 2004);
    }

    @Test
    @DisplayName("deleteUser：目标是 superAdmin → 403/2018")
    void deleteUser_superAdmin_forbidden() {
        when(userCacheService.getById(9L)).thenReturn(user(9L, "superAdmin"));

        assertBusinessError(() -> service.deleteUser(9L), HttpStatus.FORBIDDEN, 2018);

        verify(authIdentityService, never()).deleteByUserId(any());
    }

    // ---------- resetPassword ----------

    @Test
    @DisplayName("resetPassword：目标是 superAdmin → 403/2019")
    void resetPassword_superAdmin_forbidden() {
        when(userCacheService.getById(3L)).thenReturn(user(3L, "superAdmin"));

        assertBusinessError(() -> service.resetPassword(3L, resetRequest("x")),
                            HttpStatus.FORBIDDEN, 2019);

        verify(authIdentityMapper, never()).updateById(any(SysAuthIdentity.class));
    }

    @Test
    @DisplayName("resetPassword：未绑定本地凭证 → 400/2002")
    void resetPassword_noLocalIdentity_badRequest() {
        when(userCacheService.getById(3L)).thenReturn(user(3L, "bob"));
        when(authIdentityMapper.selectOne(any())).thenReturn(null);

        assertBusinessError(() -> service.resetPassword(3L, resetRequest("x")),
                            HttpStatus.BAD_REQUEST, 2002);

        verify(authIdentityMapper, never()).updateById(any(SysAuthIdentity.class));
    }

    @Test
    @DisplayName("resetPassword：成功 → 写入新 BCrypt 凭证并更新")
    void resetPassword_success_updatesCredential() {
        when(userCacheService.getById(3L)).thenReturn(user(3L, "bob"));
        SysAuthIdentity identity = new SysAuthIdentity();
        identity.setUserId(3L);
        identity.setIdentityType("local");
        when(authIdentityMapper.selectOne(any())).thenReturn(identity);
        when(passwordEncoder.encode("newhash")).thenReturn("ENC2");

        service.resetPassword(3L, resetRequest("newhash"));

        assertThat(identity.getCredential()).isEqualTo("ENC2");
        verify(authIdentityMapper).updateById(identity);
    }

    // ---------- helpers ----------

    private static SysUser user(Long userId, String userCode) {
        SysUser u = new SysUser();
        u.setUserId(userId);
        u.setUserCode(userCode);
        u.setStatus(1);
        return u;
    }

    private static UserCreateRequest createRequest(String userCode) {
        UserCreateRequest r = new UserCreateRequest();
        r.setUserCode(userCode);
        r.setUserName("新用户");
        r.setDeptId(10L);
        r.setPassword("deadbeef");
        return r;
    }

    private static UserUpdateRequest updateRequest() {
        UserUpdateRequest r = new UserUpdateRequest();
        r.setUserName("改名");
        r.setDeptId(10L);
        r.setStatus(1);
        r.setVersion(0);
        return r;
    }

    private static ResetPasswordRequest resetRequest(String password) {
        ResetPasswordRequest r = new ResetPasswordRequest();
        r.setPassword(password);
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

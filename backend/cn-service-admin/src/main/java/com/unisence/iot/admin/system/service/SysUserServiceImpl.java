package com.unisence.iot.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.unisence.iot.admin.system.dto.UserQuery;
import com.unisence.iot.admin.system.dto.UserUpdateRequest;
import com.unisence.iot.admin.system.vo.UserFormOptionsVO;
import com.unisence.iot.admin.system.vo.UserVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends BaseServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final SysUserMapper userMapper;
    private final SysAuthIdentityMapper authIdentityMapper;
    private final SysAuthIdentityService authIdentityService;
    private final SysUserRoleMapper userRoleMapper;
    private final SysDeptService deptService;
    private final PasswordEncoder passwordEncoder;
    private final CacheEvictService cacheEvictService;
    private final SysUserCacheService userCacheService;
    private final SysUserConverter userConverter;
    private final SysRoleConverter roleConverter;
    private final SysMenuMapper menuMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuService menuService;

    @Override
    public PageResult<UserVO> pageUsers(PageRequest<UserQuery> request) {
        UserQuery query = request.getQuery();
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.like(StringUtils.hasText(query.getUserCode()), SysUser::getUserCode, query.getUserCode())
                .like(StringUtils.hasText(query.getUserName()), SysUser::getUserName, query.getUserName())
                .eq(StringUtils.hasText(query.getPhone()), SysUser::getPhone, query.getPhone())
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus());

            if (query.getDeptId() != null) {
                List<Long> deptIds = deptService.listDescendantIds(query.getDeptId());
                if (deptIds.isEmpty()) {
                    return new PageResult<>(List.of(), 0);
                }
                wrapper.in(SysUser::getDeptId, deptIds);
            }
        }

        wrapper.orderByDesc(SysUser::getCreateTime);
        Page<SysUser> page = userMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()),
            wrapper);
        List<UserVO> list = page.getRecords().stream().map(userConverter::toVO).toList();
        populateRoleAssignments(list);
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public UserFormOptionsVO getFormOptions() {
        UserFormOptionsVO options = new UserFormOptionsVO();
        options.setDepartments(deptService.getDeptTree(null, null));
        options.setRoles(roleMapper.selectList(new LambdaQueryWrapper<SysRole>().ne(SysRole::getRoleCode, "superAdmin"))
                             .stream().map(roleConverter::toVO).toList());
        return options;
    }

    @Override
    @Transactional
    public Long createUser(UserCreateRequest request) {
        if ("superAdmin".equals(request.getUserCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 2019, "系统根基特权，禁止物理创建");
        }
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                                                .eq(SysUser::getUserCode, request.getUserCode()));
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2001, "用户编码已存在");
        }

        SysUser user = userConverter.toEntity(request);
        userMapper.insert(user);

        SysAuthIdentity identity = new SysAuthIdentity();
        identity.setUserId(user.getUserId());
        identity.setIdentityType("local");
        identity.setIdentifier(request.getUserCode());
        identity.setCredential(passwordEncoder.encode(request.getPassword()));
        authIdentityMapper.insert(identity);

        bindRoles(user.getUserId(), request.getRoleIds());
        return user.getUserId();
    }

    @Override
    @Transactional
    public void updateUser(Long userId, UserUpdateRequest request) {
        SysUser user = requireUser(userId);
        if ("superAdmin".equals(user.getUserCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 2017, "系统根基特权，禁止物理变更");
        }
        user.setVersion(request.getVersion());
        userConverter.updateEntity(user, request);
        userMapper.updateByIdWithVersionCheck(user);

        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        bindRoles(userId, request.getRoleIds());

        userCacheService.evict(userId);
        // 👑 注意：用户角色变更后，前端权限缓存需要刷新
        cacheEvictService.scheduleEvict(CacheDomain.USER, ClearScope.ENTRY, String.valueOf(userId));
        cacheEvictService.scheduleEvict(CacheDomain.USER_CODE, ClearScope.ENTRY, user.getUserCode());
    }

    @Override
    @Transactional
    public void deleteUser(Long userId) {
        SysUser user = requireUser(userId);
        if ("superAdmin".equals(user.getUserCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 2018, "系统根基特权，禁止物理删除");
        }
        // 👑 铁律执行：改用 this.removeById，实现 deleted = userId
        this.removeById(userId);

        authIdentityService.deleteByUserId(userId);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));

        userCacheService.evict(userId);
        cacheEvictService.scheduleEvict(CacheDomain.USER, ClearScope.ENTRY, String.valueOf(userId));
        cacheEvictService.scheduleEvict(CacheDomain.USER_CODE, ClearScope.ENTRY, user.getUserCode());
    }

    @Override
    @Transactional
    public void resetPassword(Long userId, ResetPasswordRequest request) {
        SysUser user = requireUser(userId);
        if ("superAdmin".equals(user.getUserCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 2019, "系统根基特权，禁止修改密码");
        }
        SysAuthIdentity identity = authIdentityMapper.selectOne(new LambdaQueryWrapper<SysAuthIdentity>()
                                                                    .eq(SysAuthIdentity::getUserId, userId)
                                                                    .eq(SysAuthIdentity::getIdentityType, "local"));
        if (identity == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2002, "用户未绑定本地账密凭证");
        }
        identity.setCredential(passwordEncoder.encode(request.getPassword()));
        authIdentityMapper.updateById(identity);
    }

    @Override
    public UserVO getUserProfile() {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = requireUser(userId);
        UserVO vo = toVO(user);

        // 实时获取最新的角色、权限点及侧边栏菜单树
        vo.setRoles(roleMapper.selectRoleCodesByUserId(userId));
        vo.setMenus(menuService.getSidebarMenuTree());

        // 特殊处理超级管理员身份
        if ("superAdmin".equals(user.getUserCode())) {
            vo.setPerms(List.of("*"));
        } else {
            vo.setPerms(menuMapper.selectPermsByUserId(userId));
        }

        return vo;
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userCacheService.getById(userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 2004, "用户不存在");
        }
        return user;
    }

    private UserVO toVO(SysUser user) {
        UserVO vo = userConverter.toVO(user);
        vo.setRoleIds(userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                                                    .eq(SysUserRole::getUserId, user.getUserId()))
                          .stream()
                          .map(SysUserRole::getRoleId)
                          .filter(Objects::nonNull)
                          .toList());
        return vo;
    }

    private void populateRoleAssignments(List<UserVO> users) {
        if (users.isEmpty()) {
            return;
        }
        List<Long> userIds = users.stream().map(UserVO::getUserId).filter(Objects::nonNull).toList();
        Map<Long, List<UserRoleAssignmentRow>> assignmentsByUserId = roleMapper
            .selectRoleAssignmentsByUserIds(userIds)
            .stream()
            .collect(Collectors.groupingBy(UserRoleAssignmentRow::getUserId));

        users.forEach(user -> {
            List<UserRoleAssignmentRow> assignments = assignmentsByUserId.getOrDefault(user.getUserId(), List.of());
            user.setRoleIds(assignments.stream().map(UserRoleAssignmentRow::getRoleId).toList());
            user.setRoleNames(assignments.stream().map(UserRoleAssignmentRow::getRoleName).toList());
        });
    }
}

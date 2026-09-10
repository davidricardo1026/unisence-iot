package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.cache.CacheEvictService;
import com.unisence.iot.admin.entity.SysRole;
import com.unisence.iot.admin.entity.SysRoleMenu;
import com.unisence.iot.admin.entity.SysUserRole;
import com.unisence.iot.admin.mapper.SysRoleMapper;
import com.unisence.iot.admin.mapper.SysRoleMenuMapper;
import com.unisence.iot.admin.mapper.SysUserRoleMapper;
import com.unisence.iot.admin.system.converter.SysRoleConverter;
import com.unisence.iot.admin.system.dto.RoleCreateRequest;
import com.unisence.iot.admin.system.dto.RoleQuery;
import com.unisence.iot.admin.system.dto.RoleUpdateRequest;
import com.unisence.iot.admin.system.vo.RoleFormOptionsVO;
import com.unisence.iot.admin.system.vo.RoleVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends BaseServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final CacheEvictService cacheEvictService;
    private final SysUserCacheService userCacheService;
    private final SysRoleConverter roleConverter;
    private final SysMenuService menuService;

    @Override
    public PageResult<RoleVO> pageRoles(PageRequest<RoleQuery> request) {
        RoleQuery query = request.getQuery();
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(SysRole::getRoleCode, "superAdmin"); // 👑 特权角色代码隐藏（Read Filter）

        if (query != null) {
            wrapper.like(StringUtils.hasText(query.getRoleName()), SysRole::getRoleName, query.getRoleName())
                .like(StringUtils.hasText(query.getRoleCode()), SysRole::getRoleCode, query.getRoleCode())
                .eq(query.getStatus() != null, SysRole::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(SysRole::getCreateTime);

        Page<SysRole> page = roleMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()),
            wrapper);
        List<RoleVO> list = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public RoleVO getRole(Long roleId) {
        return toVO(requireRole(roleId));
    }

    @Override
    public RoleFormOptionsVO getFormOptions() {
        RoleFormOptionsVO options = new RoleFormOptionsVO();
        options.setMenus(menuService.getMenuTree(null, null, null));
        return options;
    }

    @Override
    @Transactional
    public Long createRole(RoleCreateRequest request) {
        if ("superAdmin".equals(request.getRoleCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 2014, "系统根基特权，禁止物理创建");
        }
        assertRoleCodeUnique(request.getRoleCode(), null);

        SysRole role = roleConverter.toEntity(request);
        roleMapper.insert(role);

        bindMenus(role.getRoleId(), request.getMenuIds());
        return role.getRoleId();
    }

    @Override
    @Transactional
    public void updateRole(Long roleId, RoleUpdateRequest request) {
        SysRole role = requireRole(roleId);
        if ("superAdmin".equals(role.getRoleCode()) || "superAdmin".equals(request.getRoleCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 2015, "系统根基特权，禁止物理变更");
        }
        role.setVersion(request.getVersion());
        assertRoleCodeUnique(request.getRoleCode(), roleId);

        roleConverter.updateEntity(role, request);
        roleMapper.updateByIdWithVersionCheck(role);

        bindMenus(roleId, request.getMenuIds());

        // 👑 注意：权限变更后，由于前端用户信息中包含权限列表快照，受影响用户需要重新登录或刷新。
        // 后续可通过 /system/users/me 接口实现前端静默刷新。
        cacheEvictService.scheduleEvict(CacheDomain.ROLE, ClearScope.ENTRY, String.valueOf(roleId));
        userCacheService.evictAllPerms();
    }

    @Override
    @Transactional
    public void deleteRole(Long roleId) {
        SysRole role = requireRole(roleId);
        if ("superAdmin".equals(role.getRoleCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 2016, "系统根基特权，禁止物理删除");
        }
        Long boundCount = userRoleMapper.selectCount(
            new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId));
        if (boundCount != null && boundCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2006, "角色已被用户绑定，无法删除");
        }

        // 👑 铁律执行：改用 this.removeById
        this.removeById(roleId);

        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        cacheEvictService.scheduleEvict(CacheDomain.ROLE, ClearScope.ENTRY, String.valueOf(roleId));
    }

    private void assertRoleCodeUnique(String roleCode, Long excludeRoleId) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
            .eq(SysRole::getRoleCode, roleCode);
        if (excludeRoleId != null) {
            wrapper.ne(SysRole::getRoleId, excludeRoleId);
        }
        Long count = roleMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2007, "角色编码已存在");
        }
    }

    private void bindMenus(Long roleId, List<Long> menuIds) {
        // 1. 显式物理删除现有绑定
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));

        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }

        // 2. 循环插入新绑定（在 @Transactional 下，性能满足需求且兼容性最强）
        menuIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .forEach(menuId -> {
                SysRoleMenu roleMenu = new SysRoleMenu();
                roleMenu.setRoleId(roleId);
                roleMenu.setMenuId(menuId);
                roleMenuMapper.insert(roleMenu);
            });
    }

    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 2008, "角色不存在");
        }
        return role;
    }

    private RoleVO toVO(SysRole role) {
        RoleVO vo = roleConverter.toVO(role);
        vo.setMenuIds(roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                                                    .eq(SysRoleMenu::getRoleId, role.getRoleId()))
                          .stream()
                          .map(SysRoleMenu::getMenuId)
                          .filter(Objects::nonNull)
                          .toList());
        return vo;
    }
}

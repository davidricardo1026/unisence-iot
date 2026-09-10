package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.cache.RedisCacheNames;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.mapper.SysMenuMapper;
import com.unisence.iot.admin.mapper.SysRoleMapper;
import com.unisence.iot.admin.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysUserCacheService {

    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMapper roleMapper;

    @Cacheable(cacheNames = RedisCacheNames.SYS_USER, key = "#userId", unless = "#result == null")
    public SysUser getById(Long userId) {
        return userMapper.selectById(userId);
    }

    @Cacheable(cacheNames = RedisCacheNames.SYS_USER_PERMS, key = "#userId")
    public List<String> getPermsByUserId(Long userId) {
        return menuMapper.selectPermsByUserId(userId);
    }

    @Cacheable(cacheNames = RedisCacheNames.SYS_USER_ROLES, key = "#userId")
    public List<String> getRoleCodesByUserId(Long userId) {
        return roleMapper.selectRoleCodesByUserId(userId);
    }

    /**
     * 用户 CUD 后同时清除该用户的实体、权限点、角色码三份缓存
     */
    @Caching(evict = {
        @CacheEvict(cacheNames = RedisCacheNames.SYS_USER, key = "#userId"),
        @CacheEvict(cacheNames = RedisCacheNames.SYS_USER_PERMS, key = "#userId"),
        @CacheEvict(cacheNames = RedisCacheNames.SYS_USER_ROLES, key = "#userId")
    })
    public void evict(Long userId) {
    }

    /**
     * 角色菜单变更后清除所有用户的权限点缓存（无法精确到具体用户）
     */
    @CacheEvict(cacheNames = RedisCacheNames.SYS_USER_PERMS, allEntries = true)
    public void evictAllPerms() {
    }
}

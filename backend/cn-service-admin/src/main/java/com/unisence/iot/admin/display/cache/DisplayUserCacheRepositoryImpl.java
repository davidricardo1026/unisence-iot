package com.unisence.iot.admin.display.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class DisplayUserCacheRepositoryImpl implements DisplayUserCacheRepository {

    private final SysUserMapper userMapper;
    private final Map<String, Cache<Object, Object>> caffeineCacheMap;

    @Override
    public Map<Long, String> getDisplayNames(Set<Long> userIds, String l1CacheName) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Cache<Object, Object> cache = caffeineCacheMap.get(l1CacheName);
        Map<Long, String> result = new HashMap<>();
        Set<Long> missed = new HashSet<>();

        if (cache != null) {
            Map<Object, Object> present = cache.getAllPresent(userIds);
            for (Long userId : userIds) {
                Object value = present.get(userId);
                if (value instanceof String name) {
                    result.put(userId, name);
                } else {
                    missed.add(userId);
                }
            }
        } else {
            missed.addAll(userIds);
        }

        if (!missed.isEmpty()) {
            userMapper.selectList(new LambdaQueryWrapper<SysUser>().in(SysUser::getUserId, missed))
                .forEach(user -> {
                    String displayName = user.getUserName() != null && !user.getUserName().isBlank()
                        ? user.getUserName()
                        : user.getUserCode();
                    result.put(user.getUserId(), displayName);
                    if (cache != null) {
                        cache.put(user.getUserId(), displayName);
                    }
                });
        }
        return result;
    }
}

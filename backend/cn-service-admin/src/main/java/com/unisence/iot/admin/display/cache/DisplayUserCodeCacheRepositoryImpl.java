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
public class DisplayUserCodeCacheRepositoryImpl implements DisplayUserCodeCacheRepository {

    private final SysUserMapper userMapper;
    private final Map<String, Cache<Object, Object>> caffeineCacheMap;

    @Override
    public Map<String, String> getDisplayNames(Set<String> userCodes, String l1CacheName) {
        if (userCodes == null || userCodes.isEmpty()) {
            return Map.of();
        }
        Cache<Object, Object> cache = caffeineCacheMap.get(l1CacheName);
        Map<String, String> result = new HashMap<>();
        Set<String> missed = new HashSet<>();

        if (cache != null) {
            Map<Object, Object> present = cache.getAllPresent(userCodes);
            for (String userCode : userCodes) {
                Object value = present.get(userCode);
                if (value instanceof String name) {
                    result.put(userCode, name);
                } else {
                    missed.add(userCode);
                }
            }
        } else {
            missed.addAll(userCodes);
        }

        if (!missed.isEmpty()) {
            userMapper.selectList(new LambdaQueryWrapper<SysUser>().in(SysUser::getUserCode, missed))
                .forEach(user -> {
                    String displayName = user.getUserName() != null && !user.getUserName().isBlank()
                        ? user.getUserName()
                        : user.getUserCode();
                    result.put(user.getUserCode(), displayName);
                    if (cache != null) {
                        cache.put(user.getUserCode(), displayName);
                    }
                });
        }
        return result;
    }
}

package com.unisence.iot.admin.display.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.unisence.iot.admin.entity.SysDept;
import com.unisence.iot.admin.mapper.SysDeptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class DisplayDeptCacheRepositoryImpl implements DisplayDeptCacheRepository {

    private final SysDeptMapper deptMapper;
    private final Map<String, Cache<Object, Object>> caffeineCacheMap;

    @Override
    public Map<Long, String> getDisplayNames(Set<Long> deptIds, String l1CacheName) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Map.of();
        }
        Cache<Object, Object> cache = caffeineCacheMap.get(l1CacheName);
        Map<Long, String> result = new HashMap<>();
        Set<Long> missed = new HashSet<>();

        if (cache != null) {
            Map<Object, Object> present = cache.getAllPresent(deptIds);
            for (Long deptId : deptIds) {
                Object value = present.get(deptId);
                if (value instanceof String name) {
                    result.put(deptId, name);
                } else {
                    missed.add(deptId);
                }
            }
        } else {
            missed.addAll(deptIds);
        }

        if (!missed.isEmpty()) {
            deptMapper.selectList(new LambdaQueryWrapper<SysDept>().in(SysDept::getDeptId, missed))
                .forEach(dept -> {
                    result.put(dept.getDeptId(), dept.getDeptName());
                    if (cache != null) {
                        cache.put(dept.getDeptId(), dept.getDeptName());
                    }
                });
        }
        return result;
    }
}

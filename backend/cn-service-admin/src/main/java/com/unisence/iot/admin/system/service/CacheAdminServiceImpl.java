package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.cache.CacheEvictService;
import com.unisence.iot.admin.system.dto.CacheClearRequest;
import com.unisence.iot.admin.system.vo.CacheRegistryItemVO;
import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CacheAdminServiceImpl implements CacheAdminService {

    private static final Map<CacheDomain, String> RELATED_BUSINESS = Map.of(
        CacheDomain.USER, "用户管理 CUD（userId → 姓名）",
        CacheDomain.USER_CODE, "用户管理 CUD（userCode → 姓名）",
        CacheDomain.DEPT, "部门管理 CUD",
        CacheDomain.ROLE, "角色管理 CUD"
    );

    private final CacheEvictService cacheEvictService;

    @Override
    public List<CacheRegistryItemVO> listRegistry() {
        return Arrays.stream(CacheDomain.values()).map(domain -> {
            CacheRegistryItemVO item = new CacheRegistryItemVO();
            item.setDomain(domain.name());
            item.setCacheName(domain.cacheName());
            item.setCaffeineCacheName(domain.caffeineCacheName());
            item.setRelatedBusiness(RELATED_BUSINESS.get(domain));
            item.setSupportsScopes(List.of(ClearScope.ALL));
            return item;
        }).toList();
    }

    @Override
    @Transactional
    public void clear(CacheClearRequest request) {
        cacheEvictService.scheduleEvict(request.getDomain(), request.getScope(), null);
    }
}

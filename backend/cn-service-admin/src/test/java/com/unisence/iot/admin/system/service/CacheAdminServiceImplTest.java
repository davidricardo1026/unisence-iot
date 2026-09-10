package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.cache.CacheEvictService;
import com.unisence.iot.admin.system.dto.CacheClearRequest;
import com.unisence.iot.admin.system.vo.CacheRegistryItemVO;
import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link CacheAdminServiceImpl} 单元测试。
 * 覆盖：缓存域注册表装配（每个 {@link CacheDomain} 一项、字段齐全、仅支持 ALL），以及清理委托给 {@link CacheEvictService}。
 */
class CacheAdminServiceImplTest {

    private final CacheEvictService cacheEvictService = mock(CacheEvictService.class);
    private final CacheAdminServiceImpl service = new CacheAdminServiceImpl(cacheEvictService);

    @Test
    @DisplayName("listRegistry：每个 CacheDomain 一项，name/cacheName/caffeineCacheName/relatedBusiness 齐全，仅支持 ALL")
    void listRegistry_mapsEveryDomain() {
        List<CacheRegistryItemVO> registry = service.listRegistry();

        assertThat(registry).hasSize(CacheDomain.values().length);
        CacheRegistryItemVO user = registry.stream()
            .filter(i -> "USER".equals(i.getDomain())).findFirst().orElseThrow();
        assertThat(user.getCacheName()).isEqualTo("user");
        assertThat(user.getCaffeineCacheName()).isEqualTo("display-user");
        assertThat(user.getRelatedBusiness()).isNotBlank();
        assertThat(user.getSupportsScopes()).containsExactly(ClearScope.ALL);
    }

    @Test
    @DisplayName("clear：透传 domain + scope 给 CacheEvictService，key 传 null（整库清理）")
    void clear_delegatesToEvictService() {
        CacheClearRequest request = new CacheClearRequest();
        request.setDomain(CacheDomain.ROLE);
        request.setScope(ClearScope.ALL);

        service.clear(request);

        verify(cacheEvictService).scheduleEvict(CacheDomain.ROLE, ClearScope.ALL, null);
    }
}

package com.unisence.iot.admin.cache;

import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CacheEvictService {

    private final ApplicationEventPublisher eventPublisher;

    public void scheduleEvict(CacheDomain domain, ClearScope scope, String key) {
        eventPublisher.publishEvent(new CacheEvictEvent(domain, scope, key));
    }
}

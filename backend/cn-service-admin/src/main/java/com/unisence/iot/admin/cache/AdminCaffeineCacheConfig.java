package com.unisence.iot.admin.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.unisence.iot.common.cache.CacheDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class AdminCaffeineCacheConfig {

    @Bean
    public Map<String, Cache<Object, Object>> caffeineCacheMap(LocalCacheProperties properties) {
        Map<String, Cache<Object, Object>> cacheMap = new ConcurrentHashMap<>();

        for (CacheDomain domain : CacheDomain.values()) {
            String cacheName = domain.caffeineCacheName();
            String specString = properties.getSpecs().get(cacheName);
            if (specString == null || specString.isBlank()) {
                throw new IllegalStateException(
                    "缺失 app.caffeine.specs." + cacheName + " 配置，请在 application.yml 中补齐");
            }
            CaffeineSpec spec = CaffeineSpec.parse(specString);
            cacheMap.put(cacheName, Caffeine.from(spec).build());
        }

        return cacheMap;
    }
}

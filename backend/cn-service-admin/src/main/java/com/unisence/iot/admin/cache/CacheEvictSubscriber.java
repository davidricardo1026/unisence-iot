package com.unisence.iot.admin.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.CacheEvictMessage;
import com.unisence.iot.common.cache.ClearScope;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictSubscriber {

    private final RedissonClient redissonClient;
    private final Map<String, Cache<Object, Object>> caffeineCacheMap;
    private final CacheProperties cacheProperties;

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(cacheProperties.getEvict().getChannel());
        topic.addListener(CacheEvictMessage.class, (channel, msg) -> applyLocalEvict(msg));
    }

    public void applyLocalEvict(CacheEvictMessage msg) {
        try {
            CacheDomain domain = CacheDomain.fromCacheName(msg.cacheName());
            Cache<Object, Object> cache = caffeineCacheMap.get(domain.caffeineCacheName());
            if (cache != null) {
                if (msg.scope() == ClearScope.ALL) {
                    cache.invalidateAll();
                } else {
                    cache.invalidate(parseKey(msg.key(), domain));
                }
            }
        } catch (Exception e) {
            log.error("缓存清除失败: cacheName={}, key={}", msg.cacheName(), msg.key(), e);
        }
    }

    private Object parseKey(String key, CacheDomain domain) {
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            return key;
        }
    }
}

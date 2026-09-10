package com.unisence.iot.admin.cache;

import com.unisence.iot.common.cache.CacheEvictMessage;
import com.unisence.iot.common.cache.CacheEvictPublisher;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedissonCacheEvictPublisher implements CacheEvictPublisher {

    private final RedissonClient redissonClient;
    private final CacheProperties cacheProperties;

    @Override
    public void publish(CacheEvictMessage message) {
        RTopic topic = redissonClient.getTopic(cacheProperties.getEvict().getChannel());
        topic.publish(message);
    }
}

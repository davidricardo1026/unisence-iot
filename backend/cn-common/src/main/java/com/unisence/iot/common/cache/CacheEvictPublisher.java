package com.unisence.iot.common.cache;

public interface CacheEvictPublisher {
    void publish(CacheEvictMessage message);
}

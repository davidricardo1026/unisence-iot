package com.unisence.iot.admin.display.feeder;

import com.unisence.iot.admin.display.cache.DisplayUserCacheRepository;
import com.unisence.iot.common.cache.CacheDomain;
import io.github.easytrans.core.spi.TranslationFeeder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserTranslationFeeder implements TranslationFeeder {

    private static final String L1_CACHE_NAME = CacheDomain.USER.caffeineCacheName();
    private final DisplayUserCacheRepository userCacheRepository;

    @Override
    public String getType() {
        return "USER_SERVICE";
    }

    @Override
    public Map<Object, String> batchLoad(Set<Object> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = ids.stream()
            .map(id -> ((Number) id).longValue())
            .collect(Collectors.toSet());
        return userCacheRepository.getDisplayNames(userIds, L1_CACHE_NAME).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}

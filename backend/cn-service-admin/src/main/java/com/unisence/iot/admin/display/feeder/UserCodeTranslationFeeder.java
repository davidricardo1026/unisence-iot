package com.unisence.iot.admin.display.feeder;

import com.unisence.iot.admin.display.cache.DisplayUserCodeCacheRepository;
import com.unisence.iot.common.cache.CacheDomain;
import io.github.easytrans.core.spi.TranslationFeeder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserCodeTranslationFeeder implements TranslationFeeder {

    private static final String L1_CACHE_NAME = CacheDomain.USER_CODE.caffeineCacheName();
    private final DisplayUserCodeCacheRepository userCodeCacheRepository;

    @Override
    public String getType() {
        return "USER_CODE_SERVICE";
    }

    @Override
    public Map<Object, String> batchLoad(Set<Object> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Set<String> userCodes = ids.stream()
            .map(Object::toString)
            .collect(Collectors.toSet());
        return userCodeCacheRepository.getDisplayNames(userCodes, L1_CACHE_NAME).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}

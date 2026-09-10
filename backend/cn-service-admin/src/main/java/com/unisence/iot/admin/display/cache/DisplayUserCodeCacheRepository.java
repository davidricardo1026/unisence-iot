package com.unisence.iot.admin.display.cache;

import java.util.Map;
import java.util.Set;

public interface DisplayUserCodeCacheRepository {

    Map<String, String> getDisplayNames(Set<String> userCodes, String l1CacheName);
}

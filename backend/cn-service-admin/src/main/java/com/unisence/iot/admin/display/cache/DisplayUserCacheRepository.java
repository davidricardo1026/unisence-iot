package com.unisence.iot.admin.display.cache;

import java.util.Map;
import java.util.Set;

public interface DisplayUserCacheRepository {

    Map<Long, String> getDisplayNames(Set<Long> userIds, String l1CacheName);
}

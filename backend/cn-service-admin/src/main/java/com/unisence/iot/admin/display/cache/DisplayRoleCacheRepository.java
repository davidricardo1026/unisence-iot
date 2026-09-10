package com.unisence.iot.admin.display.cache;

import java.util.Map;
import java.util.Set;

public interface DisplayRoleCacheRepository {

    Map<Long, String> getDisplayNames(Set<Long> roleIds, String l1CacheName);
}

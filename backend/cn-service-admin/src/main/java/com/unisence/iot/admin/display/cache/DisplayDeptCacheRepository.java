package com.unisence.iot.admin.display.cache;

import java.util.Map;
import java.util.Set;

public interface DisplayDeptCacheRepository {

    Map<Long, String> getDisplayNames(Set<Long> deptIds, String l1CacheName);
}

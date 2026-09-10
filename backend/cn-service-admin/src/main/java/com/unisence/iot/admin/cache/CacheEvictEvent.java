package com.unisence.iot.admin.cache;

import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;

record CacheEvictEvent(CacheDomain domain, ClearScope scope, String key) {
}

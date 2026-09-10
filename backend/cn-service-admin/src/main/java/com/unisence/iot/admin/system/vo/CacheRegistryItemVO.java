package com.unisence.iot.admin.system.vo;

import com.unisence.iot.common.cache.ClearScope;
import lombok.Data;

import java.util.List;

@Data
public class CacheRegistryItemVO {
    private String domain;
    private String cacheName;
    private String caffeineCacheName;
    private String relatedBusiness;
    private List<ClearScope> supportsScopes;
}

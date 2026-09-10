package com.unisence.iot.admin.system.dto;

import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CacheClearRequest {
    @NotNull(message = "缓存域不能为空")
    private CacheDomain domain;

    @NotNull(message = "清理范围不能为空")
    private ClearScope scope;
}

package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.ConfigCreateRequest;
import com.unisence.iot.admin.system.dto.ConfigQuery;
import com.unisence.iot.admin.system.dto.ConfigUpdateRequest;
import com.unisence.iot.admin.system.vo.ConfigVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

public interface SysConfigService {

    PageResult<ConfigVO> pageConfigs(PageRequest<ConfigQuery> request);

    Long createConfig(ConfigCreateRequest request);

    void updateConfig(Long configId, ConfigUpdateRequest request);

    void deleteConfig(Long configId);

    /**
     * 刷新缓存
     */
    void clearCache();
}

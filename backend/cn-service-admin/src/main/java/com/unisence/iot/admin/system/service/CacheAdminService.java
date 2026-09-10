package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.CacheClearRequest;
import com.unisence.iot.admin.system.vo.CacheRegistryItemVO;

import java.util.List;

public interface CacheAdminService {

    List<CacheRegistryItemVO> listRegistry();

    void clear(CacheClearRequest request);
}

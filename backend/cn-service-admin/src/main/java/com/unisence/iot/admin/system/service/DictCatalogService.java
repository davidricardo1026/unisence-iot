package com.unisence.iot.admin.system.service;

import com.unisence.iot.common.api.dict.DictCatalogVO;

public interface DictCatalogService {

    DictCatalogVO getCatalog(String sinceVersion);
}

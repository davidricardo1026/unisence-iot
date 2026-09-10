package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.DictTypeCreateRequest;
import com.unisence.iot.admin.system.dto.DictTypeQuery;
import com.unisence.iot.admin.system.dto.DictTypeUpdateRequest;
import com.unisence.iot.admin.system.vo.DictTypeVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

public interface SysDictTypeService {

    PageResult<DictTypeVO> pageDictTypes(PageRequest<DictTypeQuery> request);

    Long createDictType(DictTypeCreateRequest request);

    void updateDictType(Long dictTypeId, DictTypeUpdateRequest request);

    void deleteDictType(Long dictTypeId);
}

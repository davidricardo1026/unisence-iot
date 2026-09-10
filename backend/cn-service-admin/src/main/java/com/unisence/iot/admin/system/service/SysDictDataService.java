package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.DictDataCreateRequest;
import com.unisence.iot.admin.system.dto.DictDataQuery;
import com.unisence.iot.admin.system.dto.DictDataUpdateRequest;
import com.unisence.iot.admin.system.vo.DictDataVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

import java.util.List;

public interface SysDictDataService {

    PageResult<DictDataVO> pageDictData(PageRequest<DictDataQuery> request);

    List<DictDataVO> listByDictType(String dictType);

    Long createDictData(DictDataCreateRequest request);

    void updateDictData(Long dictDataId, DictDataUpdateRequest request);

    void deleteDictData(Long dictDataId);
}

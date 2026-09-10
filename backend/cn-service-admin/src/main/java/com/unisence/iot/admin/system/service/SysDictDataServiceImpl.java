package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysDictData;
import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.mapper.SysDictDataMapper;
import com.unisence.iot.admin.mapper.SysDictTypeMapper;
import com.unisence.iot.admin.system.converter.SysDictDataConverter;
import com.unisence.iot.admin.system.dto.DictDataCreateRequest;
import com.unisence.iot.admin.system.dto.DictDataQuery;
import com.unisence.iot.admin.system.dto.DictDataUpdateRequest;
import com.unisence.iot.admin.system.vo.DictDataVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysDictDataServiceImpl extends BaseServiceImpl<SysDictDataMapper, SysDictData> implements SysDictDataService {

    private final SysDictDataMapper dictDataMapper;
    private final SysDictTypeMapper dictTypeMapper;
    private final SysDictDataConverter dictDataConverter;

    @Override
    public PageResult<DictDataVO> pageDictData(PageRequest<DictDataQuery> request) {
        DictDataQuery query = request.getQuery();
        LambdaQueryWrapper<SysDictData> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getDictType()), SysDictData::getDictType, query.getDictType())
                .like(StringUtils.hasText(query.getDictLabel()), SysDictData::getDictLabel, query.getDictLabel());
        }
        wrapper.orderByAsc(SysDictData::getSortOrder)
            .orderByAsc(SysDictData::getDictDataId);

        Page<SysDictData> page = dictDataMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()),
            wrapper);
        List<DictDataVO> list = page.getRecords().stream().map(dictDataConverter::toVO).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public List<DictDataVO> listByDictType(String dictType) {
        return dictDataMapper.selectList(new LambdaQueryWrapper<SysDictData>()
                                             .eq(SysDictData::getDictType, dictType)
                                             .orderByAsc(SysDictData::getSortOrder))
            .stream()
            .map(dictDataConverter::toVO)
            .toList();
    }

    @Override
    @Transactional
    public Long createDictData(DictDataCreateRequest request) {
        requireDictTypeExists(request.getDictType());
        assertDictDataUnique(request.getDictType(), request.getDictLabel(), request.getDictValue(), null);
        SysDictData data = dictDataConverter.toEntity(request);
        dictDataMapper.insert(data);
        return data.getDictDataId();
    }

    @Override
    @Transactional
    public void updateDictData(Long dictDataId, DictDataUpdateRequest request) {
        SysDictData data = requireDictData(dictDataId);
        assertDictDataUnique(data.getDictType(), request.getDictLabel(), request.getDictValue(), dictDataId);
        data.setVersion(request.getVersion());
        dictDataConverter.updateEntity(data, request);
        dictDataMapper.updateByIdWithVersionCheck(data);
    }

    private void assertDictDataUnique(String dictType, String dictLabel, String dictValue, Long excludeId) {
        // 1. 校验标签唯一性
        LambdaQueryWrapper<SysDictData> labelWrapper = new LambdaQueryWrapper<SysDictData>()
            .eq(SysDictData::getDictType, dictType)
            .eq(SysDictData::getDictLabel, dictLabel);
        if (excludeId != null) {
            labelWrapper.ne(SysDictData::getDictDataId, excludeId);
        }
        if (dictDataMapper.selectCount(labelWrapper) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2024, "该字典下标签[" + dictLabel + "]已存在");
        }

        // 2. 校验键值唯一性
        LambdaQueryWrapper<SysDictData> valueWrapper = new LambdaQueryWrapper<SysDictData>()
            .eq(SysDictData::getDictType, dictType)
            .eq(SysDictData::getDictValue, dictValue);
        if (excludeId != null) {
            valueWrapper.ne(SysDictData::getDictDataId, excludeId);
        }
        if (dictDataMapper.selectCount(valueWrapper) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2025, "该字典下键值[" + dictValue + "]已存在");
        }
    }

    @Override
    @Transactional
    public void deleteDictData(Long dictDataId) {
        SysDictData data = requireDictData(dictDataId);
        // 👑 铁律执行
        this.removeById(dictDataId);
    }

    private void requireDictTypeExists(String dictType) {
        Long count = dictTypeMapper.selectCount(
            new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getDictType, dictType));
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2022, "字典类型不存在");
        }
    }

    private SysDictData requireDictData(Long dictDataId) {
        SysDictData data = dictDataMapper.selectById(dictDataId);
        if (data == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 2023, "字典数据不存在");
        }
        return data;
    }

}

package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysDictData;
import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.mapper.SysDictDataMapper;
import com.unisence.iot.admin.mapper.SysDictTypeMapper;
import com.unisence.iot.admin.system.converter.SysDictTypeConverter;
import com.unisence.iot.admin.system.dto.DictTypeCreateRequest;
import com.unisence.iot.admin.system.dto.DictTypeQuery;
import com.unisence.iot.admin.system.dto.DictTypeUpdateRequest;
import com.unisence.iot.admin.system.vo.DictTypeVO;
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
public class SysDictTypeServiceImpl extends BaseServiceImpl<SysDictTypeMapper, SysDictType> implements SysDictTypeService {

    private final SysDictTypeMapper dictTypeMapper;
    private final SysDictDataMapper dictDataMapper;
    private final SysDictTypeConverter dictTypeConverter;

    @Override
    public PageResult<DictTypeVO> pageDictTypes(PageRequest<DictTypeQuery> request) {
        DictTypeQuery query = request.getQuery();
        LambdaQueryWrapper<SysDictType> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.like(StringUtils.hasText(query.getDictName()), SysDictType::getDictName, query.getDictName())
                .like(StringUtils.hasText(query.getDictType()), SysDictType::getDictType, query.getDictType())
                .eq(query.getStatus() != null, SysDictType::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(SysDictType::getCreateTime);

        Page<SysDictType> page = dictTypeMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()),
            wrapper);
        List<DictTypeVO> list = page.getRecords().stream().map(dictTypeConverter::toVO).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    @Transactional
    public Long createDictType(DictTypeCreateRequest request) {
        assertDictTypeUnique(request.getDictType(), null);
        SysDictType type = dictTypeConverter.toEntity(request);
        dictTypeMapper.insert(type);
        return type.getDictTypeId();
    }

    @Override
    @Transactional
    public void updateDictType(Long dictTypeId, DictTypeUpdateRequest request) {
        SysDictType type = requireDictType(dictTypeId);
        type.setVersion(request.getVersion());
        assertDictTypeUnique(request.getDictType(), dictTypeId);

        String oldDictType = type.getDictType();
        dictTypeConverter.updateEntity(type, request);
        dictTypeMapper.updateByIdWithVersionCheck(type);

        if (!oldDictType.equals(request.getDictType())) {
            dictDataMapper.update(null, new LambdaUpdateWrapper<SysDictData>()
                .eq(SysDictData::getDictType, oldDictType)
                .set(SysDictData::getDictType, request.getDictType()));
        }
    }

    @Override
    @Transactional
    public void deleteDictType(Long dictTypeId) {
        SysDictType type = requireDictType(dictTypeId);
        Long dataCount = dictDataMapper.selectCount(
            new LambdaQueryWrapper<SysDictData>().eq(SysDictData::getDictType, type.getDictType()));
        if (dataCount != null && dataCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2019, "字典类型下存在数据，无法删除");
        }
        // 👑 铁律执行
        this.removeById(dictTypeId);
    }

    private void assertDictTypeUnique(String dictType, Long excludeId) {
        LambdaQueryWrapper<SysDictType> wrapper = new LambdaQueryWrapper<SysDictType>()
            .eq(SysDictType::getDictType, dictType);
        if (excludeId != null) {
            wrapper.ne(SysDictType::getDictTypeId, excludeId);
        }
        Long count = dictTypeMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2020, "字典类型已存在");
        }
    }

    private SysDictType requireDictType(Long dictTypeId) {
        SysDictType type = dictTypeMapper.selectById(dictTypeId);
        if (type == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 2021, "字典类型不存在");
        }
        return type;
    }

}

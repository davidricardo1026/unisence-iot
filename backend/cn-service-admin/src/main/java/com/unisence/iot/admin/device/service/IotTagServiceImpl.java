package com.unisence.iot.admin.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.device.converter.IotTagConverter;
import com.unisence.iot.admin.device.dto.TagQuery;
import com.unisence.iot.admin.device.dto.TagSaveRequest;
import com.unisence.iot.admin.device.dto.TagUpdateRequest;
import com.unisence.iot.admin.device.vo.TagVO;
import com.unisence.iot.admin.entity.IotProductTag;
import com.unisence.iot.admin.entity.IotTag;
import com.unisence.iot.admin.mapper.IotProductTagMapper;
import com.unisence.iot.admin.mapper.IotTagMapper;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class IotTagServiceImpl extends BaseServiceImpl<IotTagMapper, IotTag> implements IotTagService {

    private final IotTagMapper tagMapper;
    private final IotProductTagMapper productTagMapper;
    private final IotTagConverter tagConverter;

    @Override
    public PageResult<TagVO> pageTags(PageRequest<TagQuery> request) {
        TagQuery query = request.getQuery();
        LambdaQueryWrapper<IotTag> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getTagKey()), IotTag::getTagKey, query.getTagKey())
                .like(StringUtils.hasText(query.getTagValue()), IotTag::getTagValue, query.getTagValue());
        }
        wrapper.orderByDesc(IotTag::getCreateTime);
        Page<IotTag> page = tagMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()), wrapper);
        return new PageResult<>(page.getRecords().stream().map(tagConverter::toVO).toList(), page.getTotal());
    }

    @Override
    @Transactional
    public Long createTag(TagSaveRequest request) {
        String tagKey = normalizeTagKey(request.getTagKey());
        assertUniqueKey(tagKey);
        IotTag tag = tagConverter.toEntity(request);
        tag.setTagKey(tagKey);
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, 3010, "标签键已存在");
        }
        return tag.getTagId();
    }

    @Override
    @Transactional
    public void updateTag(Long tagId, TagUpdateRequest request) {
        IotTag tag = require(tagId);
        tag.setVersion(request.getVersion());
        tagConverter.updateEntity(tag, request);
        tagMapper.updateByIdWithVersionCheck(tag);
    }

    @Override
    @Transactional
    public void deleteTag(Long tagId) {
        require(tagId);
        long referenceCount = productTagMapper.selectCount(
            new LambdaQueryWrapper<IotProductTag>().eq(IotProductTag::getTagId, tagId));
        if (referenceCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 3013,
                                        "标签已被 " + referenceCount + " 个产品引用，无法删除");
        }
        removeById(tagId);
    }

    private void assertUniqueKey(String key) {
        LambdaQueryWrapper<IotTag> w = new LambdaQueryWrapper<IotTag>()
            .eq(IotTag::getTagKey, key);
        if (tagMapper.selectCount(w) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 3010, "标签键已存在");
        }
    }

    private String normalizeTagKey(String key) {
        return key.trim();
    }

    private IotTag require(Long tagId) {
        IotTag tag = tagMapper.selectById(tagId);
        if (tag == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3010, "标签不存在");
        }
        return tag;
    }
}

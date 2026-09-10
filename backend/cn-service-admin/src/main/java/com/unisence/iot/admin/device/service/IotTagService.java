package com.unisence.iot.admin.device.service;

import com.unisence.iot.admin.device.dto.TagQuery;
import com.unisence.iot.admin.device.dto.TagSaveRequest;
import com.unisence.iot.admin.device.dto.TagUpdateRequest;
import com.unisence.iot.admin.device.vo.TagVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

public interface IotTagService {

    PageResult<TagVO> pageTags(PageRequest<TagQuery> request);

    Long createTag(TagSaveRequest request);

    void updateTag(Long tagId, TagUpdateRequest request);

    void deleteTag(Long tagId);
}

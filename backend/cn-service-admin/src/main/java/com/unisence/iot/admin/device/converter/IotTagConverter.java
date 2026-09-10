package com.unisence.iot.admin.device.converter;

import com.unisence.iot.admin.device.dto.TagSaveRequest;
import com.unisence.iot.admin.device.dto.TagUpdateRequest;
import com.unisence.iot.admin.device.vo.TagVO;
import com.unisence.iot.admin.entity.IotTag;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotTagConverter {

    TagVO toVO(IotTag tag);

    @Mapping(target = "version", ignore = true)
    IotTag toEntity(TagSaveRequest request);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "tagKey", ignore = true)
    void updateEntity(@MappingTarget IotTag tag, TagUpdateRequest request);
}

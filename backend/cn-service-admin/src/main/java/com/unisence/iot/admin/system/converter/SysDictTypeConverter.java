package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.system.dto.DictTypeCreateRequest;
import com.unisence.iot.admin.system.dto.DictTypeUpdateRequest;
import com.unisence.iot.admin.system.vo.DictTypeVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysDictTypeConverter {

    // ======================== Entity → VO ========================

    DictTypeVO toVO(SysDictType type);

    // ======================== DTO → Entity ========================

    SysDictType toEntity(DictTypeCreateRequest request);

    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget SysDictType entity, DictTypeUpdateRequest request);
}

package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysDictData;
import com.unisence.iot.admin.system.dto.DictDataCreateRequest;
import com.unisence.iot.admin.system.dto.DictDataUpdateRequest;
import com.unisence.iot.admin.system.vo.DictDataVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysDictDataConverter {

    // ======================== Entity → VO ========================

    DictDataVO toVO(SysDictData data);

    // ======================== DTO → Entity ========================

    SysDictData toEntity(DictDataCreateRequest request);

    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget SysDictData entity, DictDataUpdateRequest request);
}

package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysConfig;
import com.unisence.iot.admin.system.dto.ConfigCreateRequest;
import com.unisence.iot.admin.system.dto.ConfigUpdateRequest;
import com.unisence.iot.admin.system.vo.ConfigVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysConfigConverter {

    // ======================== Entity → VO ========================

    ConfigVO toVO(SysConfig config);

    // ======================== DTO → Entity ========================

    SysConfig toEntity(ConfigCreateRequest request);

    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget SysConfig entity, ConfigUpdateRequest request);
}

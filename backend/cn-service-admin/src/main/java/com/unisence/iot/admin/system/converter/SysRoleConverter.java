package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysRole;
import com.unisence.iot.admin.system.dto.RoleCreateRequest;
import com.unisence.iot.admin.system.dto.RoleUpdateRequest;
import com.unisence.iot.admin.system.vo.RoleVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysRoleConverter {

    // ======================== Entity → VO ========================

    @Mapping(target = "menuIds", ignore = true)
    RoleVO toVO(SysRole role);

    // ======================== DTO → Entity ========================

    SysRole toEntity(RoleCreateRequest request);

    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget SysRole entity, RoleUpdateRequest request);
}

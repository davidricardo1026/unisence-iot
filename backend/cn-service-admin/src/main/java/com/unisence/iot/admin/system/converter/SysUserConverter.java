package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.system.dto.UserCreateRequest;
import com.unisence.iot.admin.system.dto.UserUpdateRequest;
import com.unisence.iot.admin.system.vo.UserVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysUserConverter {

    // ======================== Entity → VO ========================

    @Mapping(target = "roleIds", ignore = true)
    @Mapping(target = "deptName", ignore = true)
    @Mapping(target = "createByName", ignore = true)
    UserVO toVO(SysUser user);

    // ======================== DTO → Entity ========================

    SysUser toEntity(UserCreateRequest request);

    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget SysUser entity, UserUpdateRequest request);
}

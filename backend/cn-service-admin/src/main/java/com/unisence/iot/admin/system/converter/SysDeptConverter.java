package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysDept;
import com.unisence.iot.admin.system.dto.DeptCreateRequest;
import com.unisence.iot.admin.system.dto.DeptUpdateRequest;
import com.unisence.iot.admin.system.vo.DeptTreeVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysDeptConverter {

    // ======================== Entity → VO ========================

    @Mapping(target = "children", ignore = true)
    DeptTreeVO toTreeNode(SysDept dept);

    // ======================== DTO → Entity ========================

    SysDept toEntity(DeptCreateRequest request);

    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget SysDept entity, DeptUpdateRequest request);
}

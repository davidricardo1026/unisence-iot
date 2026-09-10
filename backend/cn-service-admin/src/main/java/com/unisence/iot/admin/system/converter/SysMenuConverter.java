package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysMenu;
import com.unisence.iot.admin.system.dto.MenuCreateRequest;
import com.unisence.iot.admin.system.dto.MenuUpdateRequest;
import com.unisence.iot.admin.system.vo.MenuTreeVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysMenuConverter {

    // ======================== Entity → VO ========================

    @Mapping(target = "children", ignore = true)
    MenuTreeVO toTreeNode(SysMenu menu);

    // ======================== DTO → Entity ========================

    SysMenu toEntity(MenuCreateRequest request);

    /**
     * version 由 Service 显式赋值后交给乐观锁拦截器，禁止在此覆盖
     */
    @Mapping(target = "version", ignore = true)
    void updateEntity(@MappingTarget SysMenu entity, MenuUpdateRequest request);
}

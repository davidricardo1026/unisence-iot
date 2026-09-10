package com.unisence.iot.admin.device.converter;

import com.unisence.iot.admin.device.dto.TmPropertySaveRequest;
import com.unisence.iot.admin.device.vo.TmPropertyVO;
import com.unisence.iot.admin.entity.IotTmProperty;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotTmPropertyConverter {

    TmPropertyVO toVO(IotTmProperty property);

    @Mapping(target = "version", ignore = true)
    IotTmProperty toEntity(TmPropertySaveRequest request);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "identifier", ignore = true)
    void updateEntity(@MappingTarget IotTmProperty entity, TmPropertySaveRequest request);

    @Mapping(target = "propertyId", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    IotTmProperty copy(IotTmProperty source);
}

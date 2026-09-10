package com.unisence.iot.admin.device.converter;

import com.unisence.iot.admin.device.dto.TmServiceSaveRequest;
import com.unisence.iot.admin.device.vo.TmServiceVO;
import com.unisence.iot.admin.entity.IotTmService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotTmServiceConverter {

    @Mapping(target = "inputParams", ignore = true)
    @Mapping(target = "outputParams", ignore = true)
    TmServiceVO toVO(IotTmService service);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "inputParams", ignore = true)
    @Mapping(target = "outputParams", ignore = true)
    IotTmService toEntity(TmServiceSaveRequest request);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "identifier", ignore = true)
    @Mapping(target = "inputParams", ignore = true)
    @Mapping(target = "outputParams", ignore = true)
    void updateEntity(@MappingTarget IotTmService entity, TmServiceSaveRequest request);

    @Mapping(target = "serviceId", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    IotTmService copy(IotTmService source);
}

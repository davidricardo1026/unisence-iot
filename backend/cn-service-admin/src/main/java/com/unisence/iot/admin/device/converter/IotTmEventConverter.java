package com.unisence.iot.admin.device.converter;

import com.unisence.iot.admin.device.dto.TmEventSaveRequest;
import com.unisence.iot.admin.device.vo.TmEventVO;
import com.unisence.iot.admin.entity.IotTmEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotTmEventConverter {

    @Mapping(target = "inputParams", ignore = true)
    TmEventVO toVO(IotTmEvent event);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "inputParams", ignore = true)
    IotTmEvent toEntity(TmEventSaveRequest request);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "identifier", ignore = true)
    @Mapping(target = "inputParams", ignore = true)
    void updateEntity(@MappingTarget IotTmEvent entity, TmEventSaveRequest request);

    @Mapping(target = "eventId", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    IotTmEvent copy(IotTmEvent source);
}

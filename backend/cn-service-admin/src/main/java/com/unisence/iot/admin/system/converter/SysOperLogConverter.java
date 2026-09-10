package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysOperLog;
import com.unisence.iot.admin.system.vo.OperLogVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysOperLogConverter {

    @Mapping(target = "userName", ignore = true)
    OperLogVO toVO(SysOperLog entity);
}

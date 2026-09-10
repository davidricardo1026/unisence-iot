package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysFileInfo;
import com.unisence.iot.admin.system.vo.FileInfoVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SysFileInfoConverter {

    FileInfoVO toVO(SysFileInfo entity);
}

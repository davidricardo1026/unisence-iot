package com.unisence.iot.admin.device.converter;

import com.unisence.iot.admin.device.dto.DeviceSaveRequest;
import com.unisence.iot.admin.device.vo.DeviceCreateVO;
import com.unisence.iot.admin.device.vo.DeviceVO;
import com.unisence.iot.admin.entity.IotDevice;
import com.unisence.iot.admin.entity.IotProduct;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotDeviceConverter {

    @Mapping(target = "productKey", ignore = true)
    @Mapping(target = "productName", ignore = true)
    @Mapping(target = "icon", ignore = true)
    @Mapping(target = "iconUrl", ignore = true)
    @Mapping(target = "deviceFormData", ignore = true)
    DeviceVO toVO(IotDevice device);

    @Mapping(target = "productKey", ignore = true)
    @Mapping(target = "productName", ignore = true)
    @Mapping(target = "icon", ignore = true)
    @Mapping(target = "iconUrl", ignore = true)
    @Mapping(target = "deviceFormData", ignore = true)
    DeviceCreateVO toCreateVO(IotDevice device);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "productKey", target = "productKey")
    @Mapping(source = "productName", target = "productName")
    @Mapping(source = "icon", target = "icon")
    @Mapping(source = "iconUrl", target = "iconUrl")
    void copyProduct(@MappingTarget DeviceVO vo, IotProduct product);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "deviceFormData", ignore = true)
    @Mapping(target = "status", ignore = true)
    IotDevice toEntity(DeviceSaveRequest request);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "deviceCode", ignore = true)
    @Mapping(target = "deviceFormData", ignore = true)
    @Mapping(target = "status", ignore = true)
    void updateEntity(@MappingTarget IotDevice device, DeviceSaveRequest request);
}

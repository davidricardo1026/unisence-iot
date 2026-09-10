package com.unisence.iot.admin.device.converter;

import com.unisence.iot.admin.device.dto.ProductSaveRequest;
import com.unisence.iot.admin.device.vo.ProductVO;
import com.unisence.iot.admin.entity.IotProduct;
import com.unisence.iot.admin.rule.vo.RuleProductVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotProductConverter {

    @Mapping(target = "attributes", ignore = true)
    @Mapping(target = "deviceFormSchema", ignore = true)
    @Mapping(target = "tags", ignore = true)
    ProductVO toVO(IotProduct product);

    RuleProductVO toRuleProductVO(IotProduct product);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "productKey", ignore = true)
    @Mapping(target = "attributes", ignore = true)
    @Mapping(target = "deviceFormSchema", ignore = true)
    IotProduct toEntity(ProductSaveRequest request);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "productKey", ignore = true)
    @Mapping(target = "productType", ignore = true)
    @Mapping(target = "attributes", ignore = true)
    @Mapping(target = "deviceFormSchema", ignore = true)
    void updateEntity(@MappingTarget IotProduct product, ProductSaveRequest request);

    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "productKey", ignore = true)
    @Mapping(target = "productName", ignore = true)
    @Mapping(target = "productType", ignore = true)
    @Mapping(target = "deviceFormVersion", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    IotProduct copy(IotProduct source);
}

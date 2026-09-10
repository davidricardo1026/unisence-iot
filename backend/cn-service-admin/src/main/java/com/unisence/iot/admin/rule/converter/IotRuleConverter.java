package com.unisence.iot.admin.rule.converter;

import com.unisence.iot.admin.entity.RuleEntity;
import com.unisence.iot.admin.rule.dto.RuleSaveRequest;
import com.unisence.iot.admin.rule.vo.RuleDetailVO;
import com.unisence.iot.admin.rule.vo.RuleVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotRuleConverter {

    @Mapping(target = "ruleKind", ignore = true)
    @Mapping(target = "levelCount", ignore = true)
    @Mapping(target = "productCount", ignore = true)
    @Mapping(target = "emitMode", ignore = true)
    RuleVO toVO(RuleEntity rule);

    @Mapping(target = "ruleKind", ignore = true)
    @Mapping(target = "levelCount", ignore = true)
    @Mapping(target = "productCount", ignore = true)
    @Mapping(target = "emitMode", ignore = true)
    @Mapping(target = "listenerConfig", ignore = true)
    @Mapping(target = "valueConfig", ignore = true)
    @Mapping(target = "windowConfig", ignore = true)
    @Mapping(target = "aggregateConfig", ignore = true)
    @Mapping(target = "compileResult", ignore = true)
    @Mapping(target = "levels", ignore = true)
    @Mapping(target = "products", ignore = true)
    RuleDetailVO toDetailVO(RuleEntity rule);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "ruleCode", ignore = true)
    @Mapping(target = "listenerConfig", ignore = true)
    @Mapping(target = "scriptSha256", ignore = true)
    @Mapping(target = "compileResult", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "revision", ignore = true)
    void applyBase(@MappingTarget RuleEntity rule, RuleSaveRequest request);

    @Mapping(target = "ruleCode", ignore = true)
    @Mapping(target = "productIds", ignore = true)
    @Mapping(target = "listenerConfig", ignore = true)
    @Mapping(target = "levels", ignore = true)
    @Mapping(target = "version", ignore = true)
    void copyToSaveRequest(@MappingTarget RuleSaveRequest request, RuleEntity rule);
}

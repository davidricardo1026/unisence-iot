package com.unisence.iot.admin.rule.converter;

import com.unisence.iot.admin.entity.IotRuleLevel;
import com.unisence.iot.admin.rule.dto.RuleLevelRequest;
import com.unisence.iot.admin.rule.vo.RuleLevelVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IotRuleLevelConverter {

    @Mapping(target = "thresholdConfig", ignore = true)
    @Mapping(target = "kafkaOutputs", ignore = true)
    RuleLevelVO toVO(IotRuleLevel level);

    @Mapping(target = "thresholdConfig", ignore = true)
    @Mapping(target = "kafkaOutputIds", ignore = true)
    RuleLevelRequest toRequest(IotRuleLevel level);
}

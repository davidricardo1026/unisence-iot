package com.unisence.iot.admin.rule.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 透传路由规则详情：列表字段 + 完整产品与输出绑定，供编辑表单回填。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RuleRouteDetailVO extends RuleRouteVO {

    private List<RuleProductVO> products;
    private List<KafkaOutputVO> kafkaOutputs;
}

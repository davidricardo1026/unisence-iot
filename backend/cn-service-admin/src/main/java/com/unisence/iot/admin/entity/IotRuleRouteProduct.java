package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_route_product} —— 透传路由规则与普通产品的多对多绑定（B 类关联，物理删除，仅创建审计）。
 *
 * <p>业务层强制产品为 {@code product_type=1}。删除产品时须与另两张规则产品绑定表一并清理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_route_product")
public class IotRuleRouteProduct extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private Long productId;
}

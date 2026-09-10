package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_window_product} —— 窗口规则与普通产品的多对多绑定。
 *
 * <p>结构与 {@link IotRuleInstantProduct} 完全一致，分表理由见那里。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_window_product")
public class IotRuleWindowProduct extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;
    /**
     * 业务层强制 {@code product_type=1} 的普通产品。
     */
    private Long productId;
}

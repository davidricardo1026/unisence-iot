package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_instant_product} —— 即时规则与普通产品的多对多绑定。
 *
 * <p>B 类关联表：绑定 = INSERT、解绑 = <b>物理 DELETE</b>，因此继承 {@link BaseAuditEntity}
 * （只有 create_*，无 update_* / deleted / version）—— 关联关系没有「就地修改」语义。
 *
 * <p>与窗口规则的绑定<b>分两张表</b>而不是共用一张带 {@code rule_kind} 的：
 * 绑定表会被 engine 的候选索引构建高频扫描，两张窄表比一张宽表加过滤条件更直接。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_instant_product")
public class IotRuleInstantProduct extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;
    /**
     * 业务层强制 {@code product_type=1} 的普通产品。
     */
    private Long productId;
}

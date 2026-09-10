package com.unisence.iot.admin.rule.service;

import com.unisence.iot.admin.entity.IotRuleInstant;
import com.unisence.iot.admin.mapper.IotRuleInstantMapper;
import com.unisence.iot.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 即时规则的逻辑删除入口。
 *
 * <p>存在的唯一理由是仓库铁律：<b>业务删除必须走 {@code BaseServiceImpl.removeById}</b>，
 * 由它把 {@code deleted} 置为主键 ID（而不是 0/1），配合
 * {@code UNIQUE (rule_code, deleted)} 让同一编码可以被反复创建与删除。
 * 直接调 {@code mapper.deleteById} 感知不到这段注入逻辑。
 *
 * <p>而 {@code BaseServiceImpl} 绑定单一实体类型，无法同时服务两张规则表 ——
 * 因此拆出两个只承担删除语义的薄类，业务逻辑仍集中在 {@link IotRuleServiceImpl}。
 */
@Service
public class InstantRuleStore extends BaseServiceImpl<IotRuleInstantMapper, IotRuleInstant> {
}

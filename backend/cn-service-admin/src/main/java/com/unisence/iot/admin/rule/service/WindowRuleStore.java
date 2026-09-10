package com.unisence.iot.admin.rule.service;

import com.unisence.iot.admin.entity.IotRuleWindow;
import com.unisence.iot.admin.mapper.IotRuleWindowMapper;
import com.unisence.iot.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 窗口规则的逻辑删除入口。理由见 {@link InstantRuleStore}。
 */
@Service
public class WindowRuleStore extends BaseServiceImpl<IotRuleWindowMapper, IotRuleWindow> {
}

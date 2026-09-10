package com.unisence.iot.admin.rule.service;

import com.unisence.iot.admin.rule.dto.*;
import com.unisence.iot.admin.rule.vo.RuleDetailVO;
import com.unisence.iot.admin.rule.vo.RuleProductVO;
import com.unisence.iot.admin.rule.vo.RuleVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.rule.config.RuleKind;

import java.util.List;
import java.util.Map;

/**
 * 规则管理（api-rule.json）。
 *
 * <h2>为什么读写两侧的形状不同</h2>
 * <b>写</b>用两个互不相交的请求类型（{@link InstantRuleSaveRequest} /
 * {@link WindowRuleSaveRequest}）：让「给即时规则配窗口参数」在结构上不可表达，
 * 这是整轮重设计的主线。
 *
 * <p><b>读</b>则统一带 {@link RuleKind} 参数：列表页要同时展示两类规则，
 * 详情、删除、启停都只是按 (kind, ruleId) 定位一行，行为逐字相同。
 * 为它们各写一套方法只会产生两份必须同步修改的代码。
 */
public interface IotRuleService {

    /**
     * 分页查询。{@code kind} 为 null 时同时返回两类规则。
     */
    PageResult<RuleVO> pageRules(RuleKind kind, PageRequest<RuleQuery> request);

    RuleDetailVO getRule(RuleKind kind, Long ruleId);

    Long createRule(RuleSaveRequest request);

    void updateRule(RuleKind kind, Long ruleId, RuleSaveRequest request);

    void deleteRule(RuleKind kind, Long ruleId);

    /**
     * 启用前会重新校验与编译：规则可能在停用期间因物模型变化而失效。
     */
    void changeStatus(RuleKind kind, Long ruleId, RuleStatusRequest request);

    List<RuleProductVO> listProducts(RuleKind kind, Long ruleId);

    /**
     * 试校验：跑与保存相同的校验与预编译，但不写任何表。
     */
    Map<String, Object> validateRule(RuleSaveRequest request);
}

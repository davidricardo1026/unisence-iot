package com.unisence.iot.admin.rule.service;

import com.unisence.iot.admin.rule.dto.RuleRouteQuery;
import com.unisence.iot.admin.rule.dto.RuleRouteSaveRequest;
import com.unisence.iot.admin.rule.dto.RuleStatusRequest;
import com.unisence.iot.admin.rule.vo.RuleRouteDetailVO;
import com.unisence.iot.admin.rule.vo.RuleRouteVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

import java.util.Collection;

/**
 * 透传路由规则管理（{@code RuleKind.ROUTE}）。
 *
 * <p>每次 create / update / delete / changeStatus 成功后都必须提交 {@code IOT_RULES(ruleId)} 变更范围，
 * 与即时/窗口规则共用同一收敛总线；变更范围 id 不带 kind。
 *
 * <p>业务错误码：{@code 5064} 跨规则 {@code (productId, messageType, outputId)} 冲突（HTTP 409），
 * message 形如「产品 X 的 property 已由规则 &lt;ruleCode&gt; 透传到该 Topic」。
 */
public interface IotRuleRouteService {

    PageResult<RuleRouteVO> page(PageRequest<RuleRouteQuery> request);

    RuleRouteDetailVO get(Long ruleId);

    Long create(RuleRouteSaveRequest request);

    void update(Long ruleId, RuleRouteSaveRequest request);

    void delete(Long ruleId);

    void changeStatus(Long ruleId, RuleStatusRequest request);

    /**
     * 产品删除时调用：物理删除这些产品的路由绑定，并返回受影响的路由规则 ID（供调用方记入 {@code IOT_RULES} 变更范围）。
     */
    Collection<Long> detachProducts(Collection<Long> productIds);
}

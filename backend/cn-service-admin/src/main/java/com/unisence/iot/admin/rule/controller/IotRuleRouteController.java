package com.unisence.iot.admin.rule.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.rule.dto.RuleRouteQuery;
import com.unisence.iot.admin.rule.dto.RuleRouteSaveRequest;
import com.unisence.iot.admin.rule.dto.RuleStatusRequest;
import com.unisence.iot.admin.rule.service.IotRuleRouteService;
import com.unisence.iot.admin.rule.vo.RuleRouteDetailVO;
import com.unisence.iot.admin.rule.vo.RuleRouteVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 透传路由规则管理。
 *
 * <p>与 {@code /iot/rules/instant|window} 分路径：请求体没有脚本、档位与错误策略，
 * 「给透传规则配脚本」在结构上不可表达。
 */
@RestController
@RequestMapping("/iot/rule-routes")
@RequiredArgsConstructor
public class IotRuleRouteController {

    private final IotRuleRouteService service;

    @GetMapping
    @SaCheckPermission("iot:rule-route:list")
    public PageResult<RuleRouteVO> page(PageRequest<RuleRouteQuery> request) {
        return service.page(request);
    }

    @GetMapping("/{ruleId}")
    @SaCheckPermission("iot:rule-route:query")
    public RuleRouteDetailVO get(@PathVariable Long ruleId) {
        return service.get(ruleId);
    }

    @PostMapping
    @OperLog(title = "透传路由规则", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:rule-route:add")
    public Long create(@RequestBody @Valid RuleRouteSaveRequest request) {
        return service.create(request);
    }

    @PutMapping("/{ruleId}")
    @OperLog(title = "透传路由规则", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:rule-route:edit")
    public void update(@PathVariable Long ruleId, @RequestBody @Valid RuleRouteSaveRequest request) {
        service.update(ruleId, request);
    }

    @DeleteMapping("/{ruleId}")
    @OperLog(title = "透传路由规则", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:rule-route:remove")
    public void delete(@PathVariable Long ruleId) {
        service.delete(ruleId);
    }

    @PutMapping("/{ruleId}/status")
    @OperLog(title = "透传路由规则", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:rule-route:status")
    public void changeStatus(@PathVariable Long ruleId, @RequestBody @Valid RuleStatusRequest request) {
        service.changeStatus(ruleId, request);
    }
}

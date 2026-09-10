package com.unisence.iot.admin.rule.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.rule.RuleRuntimeCapabilities;
import com.unisence.iot.admin.rule.dto.InstantRuleSaveRequest;
import com.unisence.iot.admin.rule.dto.RuleQuery;
import com.unisence.iot.admin.rule.dto.RuleStatusRequest;
import com.unisence.iot.admin.rule.dto.WindowRuleSaveRequest;
import com.unisence.iot.admin.rule.service.IotRuleService;
import com.unisence.iot.admin.rule.vo.RuleDetailVO;
import com.unisence.iot.admin.rule.vo.RuleProductVO;
import com.unisence.iot.admin.rule.vo.RuleVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.rule.config.RuleKind;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 规则管理（api-rule.json）。
 *
 * <h2>为什么是两组路径而不是一组带 kind 参数</h2>
 * {@code /iot/rules/instant/**} 与 {@code /iot/rules/window/**} 的请求体<b>类型不同</b>：
 * 前者带 {@code valueConfig}，后者带 {@code windowConfig} + {@code aggregateConfig}。
 * 用一组路径加 {@code kind} 字段的话，请求体就得是两者的并集，
 * 于是「给即时规则配窗口参数」重新变成一个可以发出、只能靠校验拦下的请求 ——
 * 而本轮重设计的主线正是让它<b>结构上不可表达</b>。
 *
 * <p>读接口共用同一套 VO：列表与详情要同时服务两类规则，而它们的展示字段几乎一致。
 *
 * <h2>脚本审计依赖默认的 saveRequestData</h2>
 * 每个写接口都<b>刻意保留 {@code @OperLog} 的默认 {@code saveRequestData = true}</b>：
 * {@code us_sys_oper_log.oper_param} 是 {@code text}，而脚本长度上限
 * （{@code app.rule.script.max-filter-script-chars} 4000 +
 * {@code max-output-script-chars} 8000）即便乘以档位上限，加上配置对象也仍低于
 * 切面 64KB 的截断阈值，因此规则脚本的每次改动都会以完整 JSON 落审计表。
 * <b>禁止为这几个接口关掉 {@code saveRequestData}</b> —— 规则脚本是可执行代码，
 * 表里只留最新版本，改动前的正文只能从审计日志里找回。
 *
 * <h2>为什么试校验用 add 权限</h2>
 * {@code /validate} 不落库，但它会真实编译脚本、消耗编译器资源，
 * 权限口径与「创建规则」一致，避免只读账号被用来当编译探针。
 */
@RestController
@RequestMapping("/iot/rules")
@RequiredArgsConstructor
public class IotRuleController {

    private final IotRuleService ruleService;
    private final RuleRuntimeCapabilities runtimeCapabilities;

    @GetMapping("/capabilities")
    @SaCheckPermission("iot:rule:list")
    public Map<String, Map<String, Boolean>> capabilities() {
        boolean windowInstalled = runtimeCapabilities.windowRuntimeInstalled();
        return Map.of(
            "instantRule", Map.of("configure", true, "activate", true, "runtimeInstalled", true),
            "windowRule", Map.of("configure", true, "activate", windowInstalled,
                                 "runtimeInstalled", windowInstalled));
    }

    // ────────────────────────── 即时规则 ──────────────────────────

    @GetMapping("/instant")
    @SaCheckPermission("iot:rule:list")
    public PageResult<RuleVO> pageInstantRules(PageRequest<RuleQuery> request) {
        return ruleService.pageRules(RuleKind.INSTANT, request);
    }

    @GetMapping("/instant/{ruleId}")
    @SaCheckPermission("iot:rule:list")
    public RuleDetailVO getInstantRule(@PathVariable Long ruleId) {
        return ruleService.getRule(RuleKind.INSTANT, ruleId);
    }

    @PostMapping("/instant")
    @OperLog(title = "即时规则管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:rule:add")
    public Long createInstantRule(@RequestBody @Valid InstantRuleSaveRequest request) {
        return ruleService.createRule(request);
    }

    @PutMapping("/instant/{ruleId}")
    @OperLog(title = "即时规则管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:rule:edit")
    public void updateInstantRule(@PathVariable Long ruleId,
                                  @RequestBody @Valid InstantRuleSaveRequest request) {
        ruleService.updateRule(RuleKind.INSTANT, ruleId, request);
    }

    @DeleteMapping("/instant/{ruleId}")
    @OperLog(title = "即时规则管理", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:rule:remove")
    public void deleteInstantRule(@PathVariable Long ruleId) {
        ruleService.deleteRule(RuleKind.INSTANT, ruleId);
    }

    @PutMapping("/instant/{ruleId}/status")
    @OperLog(title = "即时规则启停", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:rule:edit")
    public void changeInstantStatus(@PathVariable Long ruleId,
                                    @RequestBody @Valid RuleStatusRequest request) {
        ruleService.changeStatus(RuleKind.INSTANT, ruleId, request);
    }

    @GetMapping("/instant/{ruleId}/products")
    @SaCheckPermission("iot:rule:list")
    public List<RuleProductVO> listInstantProducts(@PathVariable Long ruleId) {
        return ruleService.listProducts(RuleKind.INSTANT, ruleId);
    }

    /**
     * 不落库，因此不记操作日志 —— 试校验会被前端在编辑过程中频繁调用，记录只会淹没真实变更。
     */
    @PostMapping("/instant/validate")
    @SaCheckPermission("iot:rule:add")
    public Map<String, Object> validateInstantRule(@RequestBody @Valid InstantRuleSaveRequest request) {
        return ruleService.validateRule(request);
    }

    // ────────────────────────── 窗口规则 ──────────────────────────

    @GetMapping("/window")
    @SaCheckPermission("iot:rule:list")
    public PageResult<RuleVO> pageWindowRules(PageRequest<RuleQuery> request) {
        return ruleService.pageRules(RuleKind.WINDOW, request);
    }

    @GetMapping("/window/{ruleId}")
    @SaCheckPermission("iot:rule:list")
    public RuleDetailVO getWindowRule(@PathVariable Long ruleId) {
        return ruleService.getRule(RuleKind.WINDOW, ruleId);
    }

    @PostMapping("/window")
    @OperLog(title = "窗口规则管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:rule:add")
    public Long createWindowRule(@RequestBody @Valid WindowRuleSaveRequest request) {
        return ruleService.createRule(request);
    }

    @PutMapping("/window/{ruleId}")
    @OperLog(title = "窗口规则管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:rule:edit")
    public void updateWindowRule(@PathVariable Long ruleId,
                                 @RequestBody @Valid WindowRuleSaveRequest request) {
        ruleService.updateRule(RuleKind.WINDOW, ruleId, request);
    }

    @DeleteMapping("/window/{ruleId}")
    @OperLog(title = "窗口规则管理", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:rule:remove")
    public void deleteWindowRule(@PathVariable Long ruleId) {
        ruleService.deleteRule(RuleKind.WINDOW, ruleId);
    }

    @PutMapping("/window/{ruleId}/status")
    @OperLog(title = "窗口规则启停", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:rule:edit")
    public void changeWindowStatus(@PathVariable Long ruleId,
                                   @RequestBody @Valid RuleStatusRequest request) {
        if (Integer.valueOf(1).equals(request.getStatus()) && !runtimeCapabilities.windowRuntimeInstalled()) {
            throw new BusinessException(HttpStatus.CONFLICT, 5061,
                                        "当前部署未安装窗口规则运行时，可保存和修改，但不能启用");
        }
        ruleService.changeStatus(RuleKind.WINDOW, ruleId, request);
    }

    @GetMapping("/window/{ruleId}/products")
    @SaCheckPermission("iot:rule:list")
    public List<RuleProductVO> listWindowProducts(@PathVariable Long ruleId) {
        return ruleService.listProducts(RuleKind.WINDOW, ruleId);
    }

    @PostMapping("/window/validate")
    @SaCheckPermission("iot:rule:add")
    public Map<String, Object> validateWindowRule(@RequestBody @Valid WindowRuleSaveRequest request) {
        return ruleService.validateRule(request);
    }
}

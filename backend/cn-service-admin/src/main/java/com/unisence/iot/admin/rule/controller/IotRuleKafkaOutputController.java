package com.unisence.iot.admin.rule.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.rule.dto.KafkaOutputQuery;
import com.unisence.iot.admin.rule.dto.KafkaOutputSaveRequest;
import com.unisence.iot.admin.rule.service.IotRuleKafkaOutputService;
import com.unisence.iot.admin.rule.vo.KafkaOutputVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Kafka 输出定义管理。
 *
 * <p>输出定义是平台配置，与规则脚本无关；写入 Kafka 即为平台交付完成，
 * 后续转发、重试、幂等与外部协议语义由各下游消费方负责。
 */
@RestController
@RequestMapping("/iot/rule-kafka-outputs")
@RequiredArgsConstructor
public class IotRuleKafkaOutputController {

    private final IotRuleKafkaOutputService service;

    @GetMapping
    @SaCheckPermission("iot:rule-kafka-output:list")
    public PageResult<KafkaOutputVO> page(PageRequest<KafkaOutputQuery> request) {
        return service.page(request);
    }

    @GetMapping("/{outputId}")
    @SaCheckPermission("iot:rule-kafka-output:query")
    public KafkaOutputVO get(@PathVariable Long outputId) {
        return service.get(outputId);
    }

    @PostMapping
    @OperLog(title = "Kafka 输出定义", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:rule-kafka-output:add")
    public Long create(@RequestBody @Valid KafkaOutputSaveRequest request) {
        return service.create(request);
    }

    @PutMapping("/{outputId}")
    @OperLog(title = "Kafka 输出定义", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:rule-kafka-output:edit")
    public void update(@PathVariable Long outputId, @RequestBody @Valid KafkaOutputSaveRequest request) {
        service.update(outputId, request);
    }

    @DeleteMapping("/{outputId}")
    @OperLog(title = "Kafka 输出定义", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:rule-kafka-output:remove")
    public void delete(@PathVariable Long outputId) {
        service.delete(outputId);
    }
}

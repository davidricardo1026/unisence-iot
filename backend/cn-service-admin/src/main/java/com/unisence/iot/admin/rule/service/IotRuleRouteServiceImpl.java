package com.unisence.iot.admin.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.device.converter.IotProductConverter;
import com.unisence.iot.admin.entity.IotProduct;
import com.unisence.iot.admin.entity.IotRuleRoute;
import com.unisence.iot.admin.entity.IotRuleRouteKafkaOutput;
import com.unisence.iot.admin.entity.IotRuleRouteProduct;
import com.unisence.iot.admin.mapper.IotProductMapper;
import com.unisence.iot.admin.mapper.IotRuleRouteKafkaOutputMapper;
import com.unisence.iot.admin.mapper.IotRuleRouteMapper;
import com.unisence.iot.admin.mapper.IotRuleRouteProductMapper;
import com.unisence.iot.admin.metadata.MetadataCommit;
import com.unisence.iot.admin.rule.RuleValidationSupport;
import com.unisence.iot.admin.rule.dto.RuleRouteQuery;
import com.unisence.iot.admin.rule.dto.RuleRouteSaveRequest;
import com.unisence.iot.admin.rule.dto.RuleStatusRequest;
import com.unisence.iot.admin.rule.vo.KafkaOutputVO;
import com.unisence.iot.admin.rule.vo.RuleProductVO;
import com.unisence.iot.admin.rule.vo.RuleRouteDetailVO;
import com.unisence.iot.admin.rule.vo.RuleRouteVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.config.KafkaOutputPurpose;
import com.unisence.iot.rule.config.RuleConfigErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotRuleRouteServiceImpl extends BaseServiceImpl<IotRuleRouteMapper, IotRuleRoute>
    implements IotRuleRouteService {

    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ENABLED = 1;

    private final IotRuleRouteMapper routeMapper;
    private final IotRuleRouteProductMapper routeProductMapper;
    private final IotRuleRouteKafkaOutputMapper routeOutputMapper;
    private final IotRuleKafkaOutputService kafkaOutputService;
    private final IotProductMapper productMapper;
    private final IotProductConverter productConverter;
    private final RuleValidationSupport validation;
    private final MetadataCommit metadataCommit;

    @Override
    public PageResult<RuleRouteVO> page(PageRequest<RuleRouteQuery> request) {
        RuleRouteQuery query = request.getQuery();
        List<Long> filterRuleIds = filterRuleIds(query);
        if (filterRuleIds != null && filterRuleIds.isEmpty()) {
            return new PageResult<>(List.of(), 0);
        }

        LambdaQueryWrapper<IotRuleRoute> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            if (StringUtils.hasText(query.getKeyword())) {
                wrapper.and(w -> w.like(IotRuleRoute::getRuleName, query.getKeyword())
                    .or().like(IotRuleRoute::getRuleCode, query.getKeyword()));
            }
            wrapper.eq(StringUtils.hasText(query.getMessageType()),
                       IotRuleRoute::getMessageType,
                       query.getMessageType())
                .eq(query.getStatus() != null, IotRuleRoute::getStatus, query.getStatus());
        }
        if (filterRuleIds != null) {
            wrapper.in(IotRuleRoute::getRuleId, filterRuleIds);
        }
        wrapper.orderByDesc(IotRuleRoute::getRuleId);

        Page<IotRuleRoute> page = routeMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()), wrapper);
        List<Long> ruleIds = page.getRecords().stream().map(IotRuleRoute::getRuleId).toList();
        Map<Long, Integer> productCounts = countProducts(ruleIds);
        Map<Long, List<String>> topicsByRule = loadTargetTopics(ruleIds);
        List<RuleRouteVO> list = page.getRecords().stream()
            .map(rule -> toVo(rule, productCounts.getOrDefault(rule.getRuleId(), 0),
                              topicsByRule.getOrDefault(rule.getRuleId(), List.of())))
            .toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public RuleRouteDetailVO get(Long ruleId) {
        IotRuleRoute rule = require(ruleId);
        List<Long> productIds = routeProductMapper.selectList(
                new LambdaQueryWrapper<IotRuleRouteProduct>().eq(IotRuleRouteProduct::getRuleId, ruleId))
            .stream().map(IotRuleRouteProduct::getProductId).toList();
        List<RuleProductVO> products = productIds.isEmpty()
            ? List.of()
            : productMapper.selectBatchIds(productIds).stream().map(productConverter::toRuleProductVO).toList();
        List<Long> outputIds = routeOutputMapper.selectList(
                new LambdaQueryWrapper<IotRuleRouteKafkaOutput>()
                    .eq(IotRuleRouteKafkaOutput::getRuleId, ruleId)
                    .orderByAsc(IotRuleRouteKafkaOutput::getOutputId))
            .stream().map(IotRuleRouteKafkaOutput::getOutputId).toList();
        List<KafkaOutputVO> outputs = kafkaOutputService.listByIds(outputIds);

        RuleRouteDetailVO vo = new RuleRouteDetailVO();
        fillVo(vo, rule, products.size(), outputs.stream().map(KafkaOutputVO::getTargetTopic).toList());
        vo.setProducts(products);
        vo.setKafkaOutputs(outputs);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(RuleRouteSaveRequest request) {
        validateAggregate(null, request);
        assertRuleCodeUnique(request.getRuleCode().trim(), null);

        IotRuleRoute rule = new IotRuleRoute();
        rule.setRuleCode(request.getRuleCode().trim());
        rule.setRuleName(request.getRuleName().trim());
        rule.setMessageType(request.getMessageType());
        rule.setStatus(STATUS_DISABLED);
        routeMapper.insert(rule);
        replaceBindings(rule.getRuleId(), request);
        metadataCommit.commit(MetaKeyEnum.IOT_RULES, rule.getRuleId());
        log.info("透传路由规则已创建: ruleId={} ruleCode={}", rule.getRuleId(), rule.getRuleCode());
        return rule.getRuleId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long ruleId, RuleRouteSaveRequest request) {
        IotRuleRoute rule = require(ruleId);
        validateAggregate(ruleId, request);

        rule.setRuleName(request.getRuleName().trim());
        rule.setMessageType(request.getMessageType());
        rule.setVersion(request.getVersion());
        routeMapper.updateByIdWithVersionCheck(rule);
        replaceBindings(ruleId, request);
        metadataCommit.commit(MetaKeyEnum.IOT_RULES, ruleId);
        log.info("透传路由规则已修改: ruleId={}", ruleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long ruleId) {
        require(ruleId);
        routeProductMapper.delete(new LambdaQueryWrapper<IotRuleRouteProduct>()
                                      .eq(IotRuleRouteProduct::getRuleId, ruleId));
        routeOutputMapper.delete(new LambdaQueryWrapper<IotRuleRouteKafkaOutput>()
                                     .eq(IotRuleRouteKafkaOutput::getRuleId, ruleId));
        if (!removeById(ruleId)) {
            throw new BusinessException(HttpStatus.CONFLICT, 5060, "规则删除失败，请刷新后重试");
        }
        metadataCommit.commit(MetaKeyEnum.IOT_RULES, ruleId);
        log.info("透传路由规则已删除: ruleId={}", ruleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long ruleId, RuleStatusRequest request) {
        IotRuleRoute rule = require(ruleId);
        int target = request.getStatus() == null ? STATUS_DISABLED : request.getStatus();
        if (target != STATUS_DISABLED && target != STATUS_ENABLED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                        "规则状态只能是 0(停用) 或 1(启用)");
        }
        rule.setVersion(request.getVersion());
        rule.setStatus(target);
        routeMapper.updateByIdWithVersionCheck(rule);
        metadataCommit.commit(MetaKeyEnum.IOT_RULES, ruleId);
        log.info("透传路由规则状态已变更: ruleId={} status={}", ruleId, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Collection<Long> detachProducts(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<IotRuleRouteProduct> bindings = routeProductMapper.selectList(
            new LambdaQueryWrapper<IotRuleRouteProduct>().in(IotRuleRouteProduct::getProductId, ids));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Set<Long> ruleIds = bindings.stream()
            .map(IotRuleRouteProduct::getRuleId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        routeProductMapper.delete(new LambdaQueryWrapper<IotRuleRouteProduct>()
                                      .in(IotRuleRouteProduct::getProductId, ids));
        return ruleIds;
    }

    /**
     * 保存事务内的完整聚合校验（见接口与 {@code RuleRouteSaveRequest} Javadoc 的顺序）；
     * {@code selfRuleId} 为编辑中的规则 ID，新建传 {@code null}。
     */
    private void validateAggregate(Long selfRuleId, RuleRouteSaveRequest request) {
        String messageType = request.getMessageType();
        if (!Objects.equals(messageType, MessageType.PROPERTY.code())
            && !Objects.equals(messageType, MessageType.EVENT.code())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        RuleConfigErrorCode.LISTENER_CONFIG_INVALID.code(),
                                        "透传路由只能选择产品模型的属性或事件");
        }

        List<Long> productIds = normalizeIds(request.getProductIds(), "产品");
        validation.requireNormalProducts(productIds);

        List<Long> outputIds = normalizeIds(request.getKafkaOutputIds(), "Kafka 输出");
        kafkaOutputService.requireAll(outputIds, KafkaOutputPurpose.ROUTE);

        String conflictCode = routeMapper.findConflictingRuleCode(selfRuleId, messageType, productIds, outputIds);
        if (conflictCode != null) {
            String productLabel = conflictProductLabel(productIds, conflictCode);
            throw new BusinessException(HttpStatus.CONFLICT, 5064,
                                        "产品 " + productLabel + " 的 " + messageType
                                            + " 已由规则 " + conflictCode + " 透传到该 Topic");
        }

        request.setProductIds(productIds);
        request.setKafkaOutputIds(outputIds);
    }

    /**
     * 先物理删除该规则的产品与输出绑定，再按请求整体插入。
     */
    private void replaceBindings(Long ruleId, RuleRouteSaveRequest request) {
        routeProductMapper.delete(new LambdaQueryWrapper<IotRuleRouteProduct>()
                                      .eq(IotRuleRouteProduct::getRuleId, ruleId));
        routeOutputMapper.delete(new LambdaQueryWrapper<IotRuleRouteKafkaOutput>()
                                     .eq(IotRuleRouteKafkaOutput::getRuleId, ruleId));
        for (Long productId : request.getProductIds()) {
            IotRuleRouteProduct binding = new IotRuleRouteProduct();
            binding.setRuleId(ruleId);
            binding.setProductId(productId);
            routeProductMapper.insert(binding);
        }
        for (Long outputId : request.getKafkaOutputIds()) {
            IotRuleRouteKafkaOutput binding = new IotRuleRouteKafkaOutput();
            binding.setRuleId(ruleId);
            binding.setOutputId(outputId);
            routeOutputMapper.insert(binding);
        }
    }

    private List<Long> filterRuleIds(RuleRouteQuery query) {
        if (query == null) {
            return null;
        }
        List<Long> byProduct = null;
        if (query.getProductId() != null) {
            byProduct = routeProductMapper.selectList(
                    new LambdaQueryWrapper<IotRuleRouteProduct>()
                        .eq(IotRuleRouteProduct::getProductId, query.getProductId()))
                .stream().map(IotRuleRouteProduct::getRuleId).toList();
        }
        List<Long> byOutput = null;
        if (query.getOutputId() != null) {
            byOutput = routeOutputMapper.selectList(
                    new LambdaQueryWrapper<IotRuleRouteKafkaOutput>()
                        .eq(IotRuleRouteKafkaOutput::getOutputId, query.getOutputId()))
                .stream().map(IotRuleRouteKafkaOutput::getRuleId).toList();
        }
        if (byProduct == null && byOutput == null) {
            return null;
        }
        if (byProduct == null) {
            return byOutput;
        }
        if (byOutput == null) {
            return byProduct;
        }
        Set<Long> outputSet = new LinkedHashSet<>(byOutput);
        return byProduct.stream().filter(outputSet::contains).toList();
    }

    private Map<Long, Integer> countProducts(List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        routeProductMapper.selectList(
                new LambdaQueryWrapper<IotRuleRouteProduct>().in(IotRuleRouteProduct::getRuleId, ruleIds))
            .forEach(b -> counts.merge(b.getRuleId(), 1, Integer::sum));
        return counts;
    }

    private Map<Long, List<String>> loadTargetTopics(List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        List<IotRuleRouteKafkaOutput> bindings = routeOutputMapper.selectList(
            new LambdaQueryWrapper<IotRuleRouteKafkaOutput>()
                .in(IotRuleRouteKafkaOutput::getRuleId, ruleIds)
                .orderByAsc(IotRuleRouteKafkaOutput::getOutputId));
        List<Long> outputIds = bindings.stream().map(IotRuleRouteKafkaOutput::getOutputId).distinct().toList();
        Map<Long, String> topicByOutput = new HashMap<>();
        for (KafkaOutputVO output : kafkaOutputService.listByIds(outputIds)) {
            topicByOutput.put(output.getOutputId(), output.getTargetTopic());
        }
        Map<Long, List<String>> topics = new HashMap<>();
        for (IotRuleRouteKafkaOutput binding : bindings) {
            String topic = topicByOutput.get(binding.getOutputId());
            if (topic != null) {
                topics.computeIfAbsent(binding.getRuleId(), id -> new ArrayList<>()).add(topic);
            }
        }
        return topics;
    }

    private IotRuleRoute require(Long ruleId) {
        IotRuleRoute rule = routeMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 5060, "透传路由规则不存在: " + ruleId);
        }
        return rule;
    }

    private void assertRuleCodeUnique(String ruleCode, Long excludeRuleId) {
        LambdaQueryWrapper<IotRuleRoute> wrapper = new LambdaQueryWrapper<IotRuleRoute>()
            .eq(IotRuleRoute::getRuleCode, ruleCode);
        if (excludeRuleId != null) {
            wrapper.ne(IotRuleRoute::getRuleId, excludeRuleId);
        }
        Long count = routeMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 5060, "规则编码已存在: " + ruleCode);
        }
    }

    private List<Long> normalizeIds(List<Long> ids, String label) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400, "至少选择一个" + label);
        }
        List<Long> normalized = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (normalized.size() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400, label + "列表不能包含空值或重复项");
        }
        return normalized;
    }

    private String conflictProductLabel(List<Long> productIds, String conflictCode) {
        IotRuleRoute other = routeMapper.selectOne(
            new LambdaQueryWrapper<IotRuleRoute>().eq(IotRuleRoute::getRuleCode, conflictCode));
        if (other == null) {
            return String.valueOf(productIds.get(0));
        }
        Set<Long> otherProducts = routeProductMapper.selectList(
                new LambdaQueryWrapper<IotRuleRouteProduct>().eq(IotRuleRouteProduct::getRuleId, other.getRuleId()))
            .stream().map(IotRuleRouteProduct::getProductId).collect(Collectors.toSet());
        Long shared = productIds.stream().filter(otherProducts::contains).findFirst().orElse(productIds.get(0));
        IotProduct product = productMapper.selectById(shared);
        if (product == null) {
            return String.valueOf(shared);
        }
        return product.getProductName();
    }

    private RuleRouteVO toVo(IotRuleRoute rule, int productCount, List<String> targetTopics) {
        RuleRouteVO vo = new RuleRouteVO();
        fillVo(vo, rule, productCount, targetTopics);
        return vo;
    }

    private void fillVo(RuleRouteVO vo, IotRuleRoute rule, int productCount, List<String> targetTopics) {
        vo.setRuleId(rule.getRuleId());
        vo.setRuleCode(rule.getRuleCode());
        vo.setRuleName(rule.getRuleName());
        vo.setMessageType(rule.getMessageType());
        vo.setStatus(rule.getStatus());
        vo.setProductCount(productCount);
        vo.setTargetTopics(targetTopics);
        vo.setCreateTime(rule.getCreateTime());
        vo.setUpdateTime(rule.getUpdateTime());
        vo.setVersion(rule.getVersion());
    }
}

package com.unisence.iot.admin.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.device.converter.IotProductConverter;
import com.unisence.iot.admin.entity.*;
import com.unisence.iot.admin.mapper.*;
import com.unisence.iot.admin.metadata.MetadataCommit;
import com.unisence.iot.admin.rule.RuleConfigCodec;
import com.unisence.iot.admin.rule.RuleValidationSupport;
import com.unisence.iot.admin.rule.converter.IotRuleConverter;
import com.unisence.iot.admin.rule.converter.IotRuleLevelConverter;
import com.unisence.iot.admin.rule.dto.*;
import com.unisence.iot.admin.rule.vo.RuleDetailVO;
import com.unisence.iot.admin.rule.vo.RuleLevelVO;
import com.unisence.iot.admin.rule.vo.RuleProductVO;
import com.unisence.iot.admin.rule.vo.RuleVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.rule.compiler.RuleScriptCompiler;
import com.unisence.iot.rule.config.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 规则管理（delivery-roadmap.md P1）。
 *
 * <h2>保存顺序是安全约束，不是风格偏好</h2>
 * <pre>
 * 配置校验（逐个绑定产品）→ 沙箱预编译（filter + 每个档位）→ 计算 script_sha256
 *   → 业务 DML（规则行 + 档位整体替换 + 绑定整体替换）→ revision 递增
 *   → MetadataCommit 记 IOT_RULES(ruleId)
 * </pre>
 * 校验与编译<b>必须在写库之前</b>：脏规则一旦落库，engine 会在构建候选根时才发现编译失败，
 * 而候选根是「全成功才安装」的 —— 一条坏规则会让该实例整轮收敛失败并停在 LKG，
 * 连带其它域的变更一起延迟。挡在 HTTP 400 上，代价只是这一次保存被拒。
 *
 * <h2>哪些操作要记 IOT_RULES</h2>
 * 创建、修改、删除、启停、<b>改绑定</b>五者都要 —— engine 的规则索引是
 * {@code (productKey, messageType) → 规则列表}，绑定变了索引就变了，
 * 而绑定不在规则行上，只看规则行的 update_time 是发现不了的。
 *
 * <h2>scope_id 用裸 ruleId，两类规则会撞 —— 这是有意的</h2>
 * 变更日志的 {@code scope_id} 是一个数字，而两张规则表各有独立的 {@code AUTO_INCREMENT}。
 * engine 侧的 {@code RuleMetadataLoader} 因此按该 id <b>两张表都查一次</b>，
 * 最多多读一行。替代方案（给规则域拆两个 MetaKey）要动水位合并、反熵、变更日志三处机制，
 * 而规则变更是低频操作 —— 用一次多余的行读换掉三处机制改造是划算的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotRuleServiceImpl implements IotRuleService {

    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ENABLED = 1;

    private final IotRuleInstantMapper instantMapper;
    private final IotRuleWindowMapper windowMapper;
    private final IotRuleLevelMapper levelMapper;
    private final IotRuleInstantProductMapper instantBindingMapper;
    private final IotRuleWindowProductMapper windowBindingMapper;
    private final IotRuleLevelKafkaOutputMapper levelOutputMapper;
    private final IotProductMapper productMapper;
    private final InstantRuleStore instantStore;
    private final WindowRuleStore windowStore;
    private final IotProductConverter productConverter;
    private final IotRuleConverter ruleConverter;
    private final IotRuleLevelConverter levelConverter;
    private final IotRuleKafkaOutputService kafkaOutputService;
    private final RuleConfigCodec codec;
    private final RuleValidationSupport validation;
    private final RuleScriptCompiler compiler;
    private final MetadataCommit metadataCommit;

    // ────────────────────────────── 查询 ──────────────────────────────

    @Override
    public PageResult<RuleVO> pageRules(RuleKind kind, PageRequest<RuleQuery> request) {
        RuleQuery query = request.getQuery();
        List<Long> filterRuleIds = query == null || query.getProductId() == null
            ? null : boundRuleIds(kind, query.getProductId());
        if (filterRuleIds != null && filterRuleIds.isEmpty()) {
            return new PageResult<>(List.of(), 0);
        }

        Page<? extends RuleEntity> page = switch (kind) {
            case INSTANT -> instantMapper.selectPage(
                new Page<>(request.getPageNum(), request.getPageSize()),
                wrapper(new LambdaQueryWrapper<IotRuleInstant>(), query, filterRuleIds,
                        IotRuleInstant::getRuleName, IotRuleInstant::getRuleCode,
                        IotRuleInstant::getMessageType, IotRuleInstant::getStatus, IotRuleInstant::getRuleId));
            case WINDOW -> windowMapper.selectPage(
                new Page<>(request.getPageNum(), request.getPageSize()),
                wrapper(new LambdaQueryWrapper<IotRuleWindow>(), query, filterRuleIds,
                        IotRuleWindow::getRuleName, IotRuleWindow::getRuleCode,
                        IotRuleWindow::getMessageType, IotRuleWindow::getStatus, IotRuleWindow::getRuleId));
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };

        List<Long> ruleIds = page.getRecords().stream().map(RuleEntity::getRuleId).toList();
        Map<Long, Integer> bindingCounts = countBindings(kind, ruleIds);
        Map<Long, Integer> levelCounts = countLevels(kind, ruleIds);
        List<RuleVO> list = page.getRecords().stream()
            .map(rule -> toVo(kind, rule,
                              levelCounts.getOrDefault(rule.getRuleId(), 0),
                              bindingCounts.getOrDefault(rule.getRuleId(), 0)))
            .toList();
        return new PageResult<>(list, page.getTotal());
    }

    /**
     * 两类规则的查询条件逐字相同，只是 lambda 引用的实体类不同 ——
     * 用方法引用参数化，避免把同一段条件拼装写两遍后慢慢分叉。
     */
    private <T> LambdaQueryWrapper<T> wrapper(
        LambdaQueryWrapper<T> wrapper, RuleQuery query, List<Long> filterRuleIds,
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, String> nameField,
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, String> codeField,
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, String> messageTypeField,
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, Integer> statusField,
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, Long> idField) {
        if (query != null) {
            if (StringUtils.hasText(query.getKeyword())) {
                wrapper.and(w -> w.like(nameField, query.getKeyword())
                    .or().like(codeField, query.getKeyword()));
            }
            wrapper.eq(StringUtils.hasText(query.getMessageType()), messageTypeField, query.getMessageType())
                .eq(query.getStatus() != null, statusField, query.getStatus());
        }
        if (filterRuleIds != null) {
            wrapper.in(idField, filterRuleIds);
        }
        // 重设计后规则之间不再有执行优先级（terminal / priority 已删除），
        // 列表顺序只需稳定即可，取新建在前
        wrapper.orderByDesc(idField);
        return wrapper;
    }

    @Override
    public RuleDetailVO getRule(RuleKind kind, Long ruleId) {
        RuleEntity rule = require(kind, ruleId);
        List<RuleProductVO> products = listProducts(kind, ruleId);
        List<IotRuleLevel> levels = loadLevels(kind, ruleId);

        RuleDetailVO vo = ruleConverter.toDetailVO(rule);
        fillBase(vo, kind, levels.size(), products.size());
        vo.setListenerConfig(codec.toMap(rule.getListenerConfig()));
        vo.setCompileResult(codec.toMap(rule.getCompileResult()));
        vo.setProducts(products);
        vo.setLevels(levels.stream().map(this::toLevelVo).toList());
        switch (kind) {
            case INSTANT -> {
                IotRuleInstant instant = (IotRuleInstant) rule;
                vo.setValueConfig(codec.toMap(instant.getValueConfig()));
                fillEmitMode(vo, instant);
            }
            case WINDOW -> {
                IotRuleWindow window = (IotRuleWindow) rule;
                vo.setWindowConfig(codec.toMap(window.getWindowConfig()));
                vo.setAggregateConfig(codec.toMap(window.getAggregateConfig()));
            }
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        }
        return vo;
    }

    @Override
    public List<RuleProductVO> listProducts(RuleKind kind, Long ruleId) {
        List<Long> productIds = boundProductIds(kind, ruleId);
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productMapper.selectBatchIds(productIds).stream()
            .map(productConverter::toRuleProductVO)
            .toList();
    }

    // ────────────────────────────── 写入 ──────────────────────────────

    @Override
    @Transactional
    public Long createRule(RuleSaveRequest request) {
        RuleKind kind = kindOf(request);
        normalizeOptionalFilter(request);
        if (!StringUtils.hasText(request.getRuleCode())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 5060, "创建规则必须指定规则编码");
        }
        assertRuleCodeUnique(kind, request.getRuleCode(), null);

        List<Long> productIds = normalizeProductIds(request.getProductIds());
        requireThingModelMessage(request.getMessageType());
        validation.requireNormalProducts(productIds);
        validateLevelKafkaOutputs(request);

        RuleDefinition definition = codec.toDefinition(0L, request.getRuleCode(), request, 1L);
        RuleValidationSupport.Compiled compiled = validation.validateAndCompile(
            definition, productIds, request.getFilterScript(), levelScripts(definition, request));

        RuleEntity rule = newEntity(kind);
        rule.setRuleCode(request.getRuleCode().trim());
        applySave(rule, kind, request, definition, compiled);
        // 新建默认停用：半配置好的规则不该立刻参与线上执行
        rule.setStatus(STATUS_DISABLED);
        rule.setRevision(1L);
        insert(kind, rule);

        replaceLevels(kind, rule.getRuleId(), definition, request);
        replaceBindings(kind, rule.getRuleId(), productIds);

        metadataCommit.commit(MetaKeyEnum.IOT_RULES, rule.getRuleId());
        log.info("规则已创建: kind={} ruleId={} ruleCode={} 档位数={} sha256={}",
                 kind, rule.getRuleId(), rule.getRuleCode(), definition.levels().size(),
                 compiled.scriptSha256());
        return rule.getRuleId();
    }

    @Override
    @Transactional
    public void updateRule(RuleKind kind, Long ruleId, RuleSaveRequest request) {
        assertKindMatches(kind, request);
        normalizeOptionalFilter(request);
        RuleEntity rule = require(kind, ruleId);
        // rule_code 创建后不可变：它是 engine 侧日志与排查的稳定标识，改了会让历史记录对不上
        List<Long> productIds = normalizeProductIds(request.getProductIds());
        requireThingModelMessage(request.getMessageType());
        validation.requireNormalProducts(productIds);
        validateLevelKafkaOutputs(request);

        long nextRevision = rule.getRevision() == null ? 1L : rule.getRevision() + 1;
        RuleDefinition definition = codec.toDefinition(ruleId, rule.getRuleCode(), request, nextRevision);
        RuleValidationSupport.Compiled compiled = validation.validateAndCompile(
            definition, productIds, request.getFilterScript(), levelScripts(definition, request));

        rule.setVersion(request.getVersion());
        applySave(rule, kind, request, definition, compiled);
        // revision 只在这里递增：它与 script_sha256 共同构成 engine 的编译缓存键，
        // 不递增会让 engine 复用旧编译产物，改了脚本却不生效
        rule.setRevision(nextRevision);
        update(kind, rule);

        replaceLevels(kind, ruleId, definition, request);
        replaceBindings(kind, ruleId, productIds);

        metadataCommit.commit(MetaKeyEnum.IOT_RULES, ruleId);
        log.info("规则已修改: kind={} ruleId={} revision={} sha256={}",
                 kind, ruleId, nextRevision, compiled.scriptSha256());
    }

    @Override
    @Transactional
    public void deleteRule(RuleKind kind, Long ruleId) {
        require(kind, ruleId);
        // 档位与绑定都是随规则整体存在的从属数据，物理删除
        deleteLevels(kind, ruleId);
        deleteBindings(kind, ruleId);
        // 逻辑删除必须走 Store（deleted = 主键 ID），否则 UNIQUE(rule_code, deleted) 会挡住同名重建
        boolean removed = switch (kind) {
            case INSTANT -> instantStore.removeById(ruleId);
            case WINDOW -> windowStore.removeById(ruleId);
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };
        if (!removed) {
            throw new BusinessException(HttpStatus.CONFLICT, 5060, "规则删除失败，请刷新后重试");
        }
        metadataCommit.commit(MetaKeyEnum.IOT_RULES, ruleId);
        log.info("规则已删除: kind={} ruleId={}", kind, ruleId);
    }

    @Override
    @Transactional
    public void changeStatus(RuleKind kind, Long ruleId, RuleStatusRequest request) {
        RuleEntity rule = require(kind, ruleId);
        int target = request.getStatus() == null ? STATUS_DISABLED : request.getStatus();
        if (target != STATUS_DISABLED && target != STATUS_ENABLED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                        "规则状态只能是 0(停用) 或 1(启用)");
        }
        if (target == STATUS_ENABLED) {
            // 停用期间物模型可能已变化（属性被删、类型被改），直接启用会把失败推迟到 engine
            revalidate(kind, rule);
        }
        rule.setVersion(request.getVersion());
        rule.setStatus(target);
        update(kind, rule);
        metadataCommit.commit(MetaKeyEnum.IOT_RULES, ruleId);
        log.info("规则状态已变更: kind={} ruleId={} status={}", kind, ruleId, target);
    }

    @Override
    public Map<String, Object> validateRule(RuleSaveRequest request) {
        normalizeOptionalFilter(request);
        List<Long> productIds = normalizeProductIds(request.getProductIds());
        requireThingModelMessage(request.getMessageType());
        validation.requireNormalProducts(productIds);
        validateLevelKafkaOutputs(request);
        String code = StringUtils.hasText(request.getRuleCode()) ? request.getRuleCode() : "preview";
        RuleDefinition definition = codec.toDefinition(0L, code, request, 1L);
        RuleValidationSupport.Compiled compiled = validation.validateAndCompile(
            definition, productIds, request.getFilterScript(), levelScripts(definition, request));
        return codec.toMap(buildCompileResultJson(request, definition, compiled));
    }

    // ────────────────────────────── 档位 ──────────────────────────────

    /**
     * 按 {@code definition.levels()} 的顺序取出脚本原文。
     *
     * <p>{@code definition.levels()} 在构造时已按 severity 升序排好，而请求里的顺序
     * 由前端决定 —— 必须<b>按 levelCode 对齐</b>而不是按下标一一对应。
     * 错位不会抛任何异常：「危急档」会拿到「预警档」的输出脚本，两者都能编译通过。
     */
    private List<RuleValidationSupport.LevelScripts> levelScripts(RuleDefinition definition,
                                                                  RuleSaveRequest request) {
        Map<String, RuleLevelRequest> byCode = new HashMap<>();
        for (RuleLevelRequest level : request.getLevels()) {
            byCode.put(level.getLevelCode(), level);
        }
        List<RuleValidationSupport.LevelScripts> scripts = new ArrayList<>(definition.levels().size());
        for (LevelDefinition level : definition.levels()) {
            RuleLevelRequest source = byCode.get(level.levelCode());
            if (source == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                                            RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                            "档位脚本缺失: " + level.levelCode());
            }
            scripts.add(new RuleValidationSupport.LevelScripts(
                source.getConditionScript(), source.getOutputScript()));
        }
        return scripts;
    }

    /**
     * 档位整体替换：先按 (rule_kind, rule_id) 删净再插入。
     *
     * <p>不做「按 levelId 增量更新」：档位是规则的组成部分而非独立实体，
     * 增量更新要处理「客户端漏传一个档位算删除还是保留」这类无解的歧义。
     * 整体替换让请求体就是完整的期望状态。
     */
    private void replaceLevels(RuleKind kind, Long ruleId, RuleDefinition definition,
                               RuleSaveRequest request) {
        deleteLevels(kind, ruleId);
        Map<String, RuleLevelRequest> byCode = new HashMap<>();
        for (RuleLevelRequest level : request.getLevels()) {
            byCode.put(level.getLevelCode(), level);
        }
        for (LevelDefinition level : definition.levels()) {
            RuleLevelRequest source = byCode.get(level.levelCode());
            IotRuleLevel entity = new IotRuleLevel();
            entity.setRuleKind(kind.name());
            entity.setRuleId(ruleId);
            entity.setLevelCode(level.levelCode());
            entity.setSeverity(level.severity());
            entity.setConditionKind(level.conditionKind().name());
            entity.setThresholdConfig(codec.thresholdJson(level));
            entity.setConditionScript(source.getConditionScript());
            entity.setOutputScript(source.getOutputScript());
            entity.setCooldownMillis(level.cooldownMillis());
            levelMapper.insert(entity);
            for (Long outputId : source.getKafkaOutputIds()) {
                IotRuleLevelKafkaOutput binding = new IotRuleLevelKafkaOutput();
                binding.setLevelId(entity.getLevelId());
                binding.setOutputId(outputId);
                levelOutputMapper.insert(binding);
            }
        }
    }

    private void deleteLevelKafkaOutputs(RuleKind kind, Long ruleId) {
        List<Long> levelIds = loadLevels(kind, ruleId).stream().map(IotRuleLevel::getLevelId).toList();
        if (levelIds.isEmpty()) {
            return;
        }
        levelOutputMapper.delete(new LambdaQueryWrapper<IotRuleLevelKafkaOutput>()
                                     .in(IotRuleLevelKafkaOutput::getLevelId, levelIds));
    }

    private void deleteLevels(RuleKind kind, Long ruleId) {
        deleteLevelKafkaOutputs(kind, ruleId);
        levelMapper.delete(new LambdaQueryWrapper<IotRuleLevel>()
                               .eq(IotRuleLevel::getRuleKind, kind.name())
                               .eq(IotRuleLevel::getRuleId, ruleId));
    }

    private List<IotRuleLevel> loadLevels(RuleKind kind, Long ruleId) {
        return levelMapper.selectList(new LambdaQueryWrapper<IotRuleLevel>()
                                          .eq(IotRuleLevel::getRuleKind, kind.name())
                                          .eq(IotRuleLevel::getRuleId, ruleId)
                                          .orderByAsc(IotRuleLevel::getSeverity));
    }

    private Map<Long, Integer> countLevels(RuleKind kind, List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (IotRuleLevel level : levelMapper.selectList(
            new LambdaQueryWrapper<IotRuleLevel>()
                .eq(IotRuleLevel::getRuleKind, kind.name())
                .in(IotRuleLevel::getRuleId, ruleIds))) {
            counts.merge(level.getRuleId(), 1, Integer::sum);
        }
        return counts;
    }

    private RuleLevelVO toLevelVo(IotRuleLevel level) {
        RuleLevelVO vo = levelConverter.toVO(level);
        vo.setThresholdConfig(codec.toMap(level.getThresholdConfig()));
        List<Long> outputIds = levelOutputMapper.selectList(
                new LambdaQueryWrapper<IotRuleLevelKafkaOutput>()
                    .eq(IotRuleLevelKafkaOutput::getLevelId, level.getLevelId())
                    .orderByAsc(IotRuleLevelKafkaOutput::getOutputId))
            .stream().map(IotRuleLevelKafkaOutput::getOutputId).toList();
        vo.setKafkaOutputs(kafkaOutputService.listByIds(outputIds));
        return vo;
    }

    // ────────────────────────────── 实体分派 ──────────────────────────────

    /**
     * {@code switch} 覆盖两个类别由编译器保证 —— 新增第三类规则时，
     * 这些分派点会全部编译期报错，而不是运行期落进一个 {@code default} 分支。
     */
    private RuleEntity newEntity(RuleKind kind) {
        return switch (kind) {
            case INSTANT -> new IotRuleInstant();
            case WINDOW -> new IotRuleWindow();
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };
    }

    private void insert(RuleKind kind, RuleEntity rule) {
        switch (kind) {
            case INSTANT -> instantMapper.insert((IotRuleInstant) rule);
            case WINDOW -> windowMapper.insert((IotRuleWindow) rule);
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        }
    }

    private void update(RuleKind kind, RuleEntity rule) {
        switch (kind) {
            case INSTANT -> instantMapper.updateByIdWithVersionCheck((IotRuleInstant) rule);
            case WINDOW -> windowMapper.updateByIdWithVersionCheck((IotRuleWindow) rule);
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        }
    }

    private RuleEntity require(RuleKind kind, Long ruleId) {
        RuleEntity rule = switch (kind) {
            case INSTANT -> instantMapper.selectById(ruleId);
            case WINDOW -> windowMapper.selectById(ruleId);
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };
        if (rule == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 5060,
                                        "规则不存在: " + kind.ruleKey(ruleId));
        }
        return rule;
    }

    private static RuleKind kindOf(RuleSaveRequest request) {
        if (request instanceof InstantRuleSaveRequest) {
            return RuleKind.INSTANT;
        }
        if (request instanceof WindowRuleSaveRequest) {
            return RuleKind.WINDOW;
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 1001,
                                    "未知的规则保存请求类型: " + request.getClass().getSimpleName());
    }

    /**
     * 路径上的类别必须与请求体类型一致。
     *
     * <p>正常情况下二者天然一致（控制器按路径挑选了反序列化类型），
     * 这层判断防的是将来有人给两条路径复用同一个方法。
     */
    private static void assertKindMatches(RuleKind kind, RuleSaveRequest request) {
        if (kindOf(request) != kind) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                        "请求体与路径的规则类别不一致");
        }
    }

    // ────────────────────────────── 内部 ──────────────────────────────

    /**
     * 启停复用：用库里已存的脚本与配置重跑一遍校验编译。
     */
    private void revalidate(RuleKind kind, RuleEntity rule) {
        List<IotRuleLevel> levels = loadLevels(kind, rule.getRuleId());
        RuleSaveRequest snapshot = switch (kind) {
            case INSTANT -> {
                InstantRuleSaveRequest instant = new InstantRuleSaveRequest();
                IotRuleInstant stored = (IotRuleInstant) rule;
                instant.setValueConfig(codec.toMap(stored.getValueConfig()));
                if (StringUtils.hasText(stored.getEmitMode())) {
                    instant.setEmitMode(EmitMode.valueOf(stored.getEmitMode()));
                }
                yield instant;
            }
            case WINDOW -> {
                WindowRuleSaveRequest window = new WindowRuleSaveRequest();
                window.setWindowConfig(codec.toMap(((IotRuleWindow) rule).getWindowConfig()));
                window.setAggregateConfig(codec.toMap(((IotRuleWindow) rule).getAggregateConfig()));
                yield window;
            }
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };
        List<Long> productIds = boundProductIds(kind, rule.getRuleId());
        ruleConverter.copyToSaveRequest(snapshot, rule);
        snapshot.setProductIds(productIds);
        snapshot.setListenerConfig(codec.toMap(rule.getListenerConfig()));
        snapshot.setLevels(levels.stream().map(this::toLevelRequest).toList());

        RuleDefinition definition = codec.toDefinition(rule.getRuleId(), rule.getRuleCode(), snapshot,
                                                       rule.getRevision() == null ? 1L : rule.getRevision());
        validation.validateAndCompile(definition, productIds, rule.getFilterScript(),
                                      levelScripts(definition, snapshot));
    }

    private RuleLevelRequest toLevelRequest(IotRuleLevel level) {
        RuleLevelRequest request = levelConverter.toRequest(level);
        request.setThresholdConfig(codec.toMap(level.getThresholdConfig()));
        request.setKafkaOutputIds(levelOutputMapper.selectList(
                new LambdaQueryWrapper<IotRuleLevelKafkaOutput>()
                    .eq(IotRuleLevelKafkaOutput::getLevelId, level.getLevelId())
                    .orderByAsc(IotRuleLevelKafkaOutput::getOutputId))
                                      .stream().map(IotRuleLevelKafkaOutput::getOutputId).toList());
        return request;
    }

    private void applySave(RuleEntity rule, RuleKind kind, RuleSaveRequest request,
                           RuleDefinition definition, RuleValidationSupport.Compiled compiled) {
        ruleConverter.applyBase(rule, request);
        // 落库的是配置对象序列化后的 JSON，不是请求原始 Map —— 见 RuleConfigCodec 类注释
        rule.setListenerConfig(codec.toJson(definition.listener()));
        rule.setScriptSha256(compiled.scriptSha256());
        rule.setCompileResult(buildCompileResultJson(request, definition, compiled));
        if (rule.getErrorPolicy() == null) {
            rule.setErrorPolicy("DLQ_MESSAGE");
        }

        switch (kind) {
            case INSTANT -> {
                IotRuleInstant instant = (IotRuleInstant) rule;
                instant.setValueConfig(codec.toJson(((InstantRuleDefinition) definition).value()));
                instant.setEmitMode(((InstantRuleSaveRequest) request).getEmitMode().name());
            }
            case WINDOW -> {
                WindowRuleDefinition window = (WindowRuleDefinition) definition;
                ((IotRuleWindow) rule).setWindowConfig(codec.toJson(window.window()));
                ((IotRuleWindow) rule).setAggregateConfig(codec.toJson(window.aggregate()));
            }
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        }
    }

    /**
     * 编译摘要。
     *
     * <p>{@code referencedIdentifiers} 合并 filter 与<b>全部档位</b>脚本，且是保守超集
     * （见 {@code ScriptStats}），只能用于「物模型删属性时反查候选规则」，不能断言精确引用。
     *
     * <p>{@code outputScriptChars} / {@code outputAstNodes} 现在是<b>各档位之和</b>：
     * 一条规则可以有多个输出脚本，取其中一个会让「这条规则有多复杂」失真。
     */
    private String buildCompileResultJson(RuleSaveRequest request, RuleDefinition definition,
                                          RuleValidationSupport.Compiled compiled) {
        Set<String> identifiers = new LinkedHashSet<>(compiled.filter().stats().referencedIdentifiers());
        int outputChars = 0;
        int outputAstNodes = 0;
        for (int i = 0; i < compiled.levels().size(); i++) {
            var level = compiled.levels().get(i);
            identifiers.addAll(level.output().stats().referencedIdentifiers());
            outputAstNodes += level.output().stats().astNodes();
            if (level.condition() != null) {
                identifiers.addAll(level.condition().stats().referencedIdentifiers());
                outputAstNodes += level.condition().stats().astNodes();
            }
            String script = request.getLevels().get(i).getOutputScript();
            outputChars += script == null ? 0 : script.length();
        }
        CompileResult result = new CompileResult(
            compiler.groovyVersion(),
            compiled.scriptSha256(),
            compiled.filter().compiledAt(),
            request.getFilterScript() == null ? 0 : request.getFilterScript().length(),
            outputChars,
            compiled.filter().stats().astNodes(),
            outputAstNodes,
            List.copyOf(identifiers),
            compiled.filter().sandboxProfile());
        return codec.toJson(result);
    }

    /**
     * 规则编码在<b>同类规则内</b>唯一。
     *
     * <p>不做跨表唯一：两类规则的编码空间本就独立（UNIQUE 建在各自表上），
     * 而运行期的身份是 {@code RuleKind + ruleId}，编码只用于人读与排查。
     * 强行跨表唯一要在应用层再查一张表，换来的只是一条没有技术必要的约束。
     */
    private void assertRuleCodeUnique(RuleKind kind, String ruleCode, Long excludeRuleId) {
        Long count = switch (kind) {
            case INSTANT -> {
                LambdaQueryWrapper<IotRuleInstant> w = new LambdaQueryWrapper<IotRuleInstant>()
                    .eq(IotRuleInstant::getRuleCode, ruleCode.trim());
                if (excludeRuleId != null) {
                    w.ne(IotRuleInstant::getRuleId, excludeRuleId);
                }
                yield instantMapper.selectCount(w);
            }
            case WINDOW -> {
                LambdaQueryWrapper<IotRuleWindow> w = new LambdaQueryWrapper<IotRuleWindow>()
                    .eq(IotRuleWindow::getRuleCode, ruleCode.trim());
                if (excludeRuleId != null) {
                    w.ne(IotRuleWindow::getRuleId, excludeRuleId);
                }
                yield windowMapper.selectCount(w);
            }
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 5060, "规则编码已存在: " + ruleCode);
        }
    }

    // ────────────────────────────── 绑定 ──────────────────────────────

    private List<Long> boundProductIds(RuleKind kind, Long ruleId) {
        return switch (kind) {
            case INSTANT -> instantBindingMapper.selectList(
                    new LambdaQueryWrapper<IotRuleInstantProduct>()
                        .eq(IotRuleInstantProduct::getRuleId, ruleId))
                .stream().map(IotRuleInstantProduct::getProductId).toList();
            case WINDOW -> windowBindingMapper.selectList(
                    new LambdaQueryWrapper<IotRuleWindowProduct>()
                        .eq(IotRuleWindowProduct::getRuleId, ruleId))
                .stream().map(IotRuleWindowProduct::getProductId).toList();
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };
    }

    private List<Long> boundRuleIds(RuleKind kind, Long productId) {
        return switch (kind) {
            case INSTANT -> instantBindingMapper.selectList(
                    new LambdaQueryWrapper<IotRuleInstantProduct>()
                        .eq(IotRuleInstantProduct::getProductId, productId))
                .stream().map(IotRuleInstantProduct::getRuleId).toList();
            case WINDOW -> windowBindingMapper.selectList(
                    new LambdaQueryWrapper<IotRuleWindowProduct>()
                        .eq(IotRuleWindowProduct::getProductId, productId))
                .stream().map(IotRuleWindowProduct::getRuleId).toList();
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        };
    }

    private void replaceBindings(RuleKind kind, Long ruleId, List<Long> productIds) {
        deleteBindings(kind, ruleId);
        for (Long productId : productIds) {
            switch (kind) {
                case INSTANT -> {
                    IotRuleInstantProduct binding = new IotRuleInstantProduct();
                    binding.setRuleId(ruleId);
                    binding.setProductId(productId);
                    instantBindingMapper.insert(binding);
                }
                case WINDOW -> {
                    IotRuleWindowProduct binding = new IotRuleWindowProduct();
                    binding.setRuleId(ruleId);
                    binding.setProductId(productId);
                    windowBindingMapper.insert(binding);
                }
                case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
            }
        }
    }

    private void deleteBindings(RuleKind kind, Long ruleId) {
        switch (kind) {
            case INSTANT -> instantBindingMapper.delete(
                new LambdaQueryWrapper<IotRuleInstantProduct>().eq(IotRuleInstantProduct::getRuleId, ruleId));
            case WINDOW -> windowBindingMapper.delete(
                new LambdaQueryWrapper<IotRuleWindowProduct>().eq(IotRuleWindowProduct::getRuleId, ruleId));
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        }
    }

    private Map<Long, Integer> countBindings(RuleKind kind, List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        switch (kind) {
            case INSTANT -> instantBindingMapper.selectList(
                    new LambdaQueryWrapper<IotRuleInstantProduct>().in(IotRuleInstantProduct::getRuleId, ruleIds))
                .forEach(b -> counts.merge(b.getRuleId(), 1, Integer::sum));
            case WINDOW -> windowBindingMapper.selectList(
                    new LambdaQueryWrapper<IotRuleWindowProduct>().in(IotRuleWindowProduct::getRuleId, ruleIds))
                .forEach(b -> counts.merge(b.getRuleId(), 1, Integer::sum));
            case ROUTE -> throw new IllegalStateException("ROUTE 不走即时/窗口规则服务");
        }
        return counts;
    }

    // ────────────────────────────── 通用 ──────────────────────────────

    private List<Long> normalizeProductIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 5061, "至少选择一个产品");
        }
        List<Long> normalized = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (normalized.size() != productIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 5061, "产品列表不能包含空值或重复项");
        }
        return normalized;
    }

    /**
     * 过滤器是可选闸门；空白表示不额外过滤。落库前规范化为确定脚本，
     * 使编译缓存、摘要和 rule-stream 都不需要处理 null 分支。
     */
    private void normalizeOptionalFilter(RuleSaveRequest request) {
        if (!StringUtils.hasText(request.getFilterScript())) {
            request.setFilterScript("return true");
        }
    }

    private void requireThingModelMessage(String messageType) {
        if (!Objects.equals(messageType, "property") && !Objects.equals(messageType, "event")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        RuleConfigErrorCode.LISTENER_CONFIG_INVALID.code(),
                                        "规则只能选择产品模型的属性或事件");
        }
    }

    private RuleVO toVo(RuleKind kind, RuleEntity rule, int levelCount, int productCount) {
        RuleVO vo = ruleConverter.toVO(rule);
        fillBase(vo, kind, levelCount, productCount);
        if (kind == RuleKind.INSTANT) {
            fillEmitMode(vo, (IotRuleInstant) rule);
        }
        return vo;
    }

    private void fillEmitMode(RuleVO vo, IotRuleInstant instant) {
        if (StringUtils.hasText(instant.getEmitMode())) {
            vo.setEmitMode(EmitMode.valueOf(instant.getEmitMode()));
        }
    }

    /**
     * 每个档位的 Kafka 输出绑定：去重后长度不变、全部存在且 {@code purpose=RULE_OUTPUT}。
     * {@code EVERY_MATCH} 不允许冷却。
     */
    private void validateLevelKafkaOutputs(RuleSaveRequest request) {
        EmitMode emitMode = request instanceof InstantRuleSaveRequest instant ? instant.getEmitMode() : null;
        for (RuleLevelRequest level : request.getLevels()) {
            List<Long> ids = level.getKafkaOutputIds();
            if (ids == null || ids.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                                            RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                            "level[" + level.getLevelCode() + "].kafkaOutputIds: 至少绑定一个 Kafka 输出");
            }
            List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
            if (distinct.size() != ids.size()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                                            RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                            "level[" + level.getLevelCode() + "].kafkaOutputIds: 不能包含空值或重复项");
            }
            try {
                kafkaOutputService.requireAll(distinct, KafkaOutputPurpose.RULE_OUTPUT);
            } catch (BusinessException e) {
                log.warn("档位 Kafka 输出校验失败: levelCode={} msg={}", level.getLevelCode(), e.getMessage(), e);
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                                            RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                            "level[" + level.getLevelCode() + "].kafkaOutputIds: " + e.getMessage());
            }
            if (emitMode == EmitMode.EVERY_MATCH && level.getCooldownMillis() != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                                            RuleConfigErrorCode.LEVEL_CONFIG_INVALID.code(),
                                            "level[" + level.getLevelCode() + "].cooldownMillis: EVERY_MATCH 模式不支持冷却");
            }
        }
    }

    private void fillBase(RuleVO vo, RuleKind kind, int levelCount, int productCount) {
        vo.setRuleKind(kind.name());
        vo.setLevelCount(levelCount);
        vo.setProductCount(productCount);
    }
}

package com.unisence.iot.admin.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.IotRuleKafkaOutput;
import com.unisence.iot.admin.mapper.IotRuleKafkaOutputMapper;
import com.unisence.iot.admin.rule.dto.KafkaOutputQuery;
import com.unisence.iot.admin.rule.dto.KafkaOutputSaveRequest;
import com.unisence.iot.admin.rule.kafka.AdminRuleOutputProperties;
import com.unisence.iot.admin.rule.kafka.KafkaTopicInspector;
import com.unisence.iot.admin.rule.vo.KafkaOutputVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.rule.config.KafkaOutputPurpose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class IotRuleKafkaOutputServiceImpl implements IotRuleKafkaOutputService {

    private final IotRuleKafkaOutputMapper outputMapper;
    private final KafkaTopicInspector topicInspector;
    private final AdminRuleOutputProperties props;

    @Override
    public PageResult<KafkaOutputVO> page(PageRequest<KafkaOutputQuery> request) {
        KafkaOutputQuery query = request.getQuery();
        LambdaQueryWrapper<IotRuleKafkaOutput> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getPurpose()), IotRuleKafkaOutput::getPurpose, query.getPurpose());
            if (StringUtils.hasText(query.getKeyword())) {
                wrapper.and(w -> w.like(IotRuleKafkaOutput::getOutputCode, query.getKeyword())
                    .or().like(IotRuleKafkaOutput::getOutputName, query.getKeyword()));
            }
        }
        wrapper.orderByDesc(IotRuleKafkaOutput::getOutputId);
        Page<IotRuleKafkaOutput> page = outputMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()), wrapper);
        List<KafkaOutputVO> list = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public KafkaOutputVO get(Long outputId) {
        return toVo(require(outputId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(KafkaOutputSaveRequest request) {
        validateTopic(request.getTargetTopic());
        assertCodeUnique(request.getOutputCode().trim(), null);
        assertTopicUnique(request.getTargetTopic(), null);

        IotRuleKafkaOutput entity = new IotRuleKafkaOutput();
        entity.setOutputCode(request.getOutputCode().trim());
        entity.setOutputName(request.getOutputName().trim());
        entity.setPurpose(request.getPurpose().name());
        entity.setTargetTopic(request.getTargetTopic());
        entity.setFormat(request.getFormat().name());
        outputMapper.insert(entity);
        log.info("Kafka 输出定义已创建: outputId={} code={} topic={}",
                 entity.getOutputId(), entity.getOutputCode(), entity.getTargetTopic());
        return entity.getOutputId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long outputId, KafkaOutputSaveRequest request) {
        IotRuleKafkaOutput existing = require(outputId);
        boolean referenced = outputMapper.countReferences(outputId) > 0;
        boolean identityChanged = !Objects.equals(existing.getOutputCode(), request.getOutputCode().trim())
            || !Objects.equals(existing.getPurpose(), request.getPurpose().name())
            || !Objects.equals(existing.getTargetTopic(), request.getTargetTopic())
            || !Objects.equals(existing.getFormat(), request.getFormat().name());
        if (referenced && identityChanged) {
            throw new BusinessException(HttpStatus.CONFLICT, 5062, "输出定义已被引用，只允许修改名称");
        }
        if (!Objects.equals(existing.getOutputCode(), request.getOutputCode().trim())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400, "输出编码创建后不可修改");
        }

        boolean topicChanged = !Objects.equals(existing.getTargetTopic(), request.getTargetTopic());
        if (topicChanged) {
            validateTopic(request.getTargetTopic());
            assertTopicUnique(request.getTargetTopic(), outputId);
        }

        existing.setOutputName(request.getOutputName().trim());
        existing.setPurpose(request.getPurpose().name());
        existing.setTargetTopic(request.getTargetTopic());
        existing.setFormat(request.getFormat().name());
        existing.setVersion(request.getVersion());
        outputMapper.updateByIdWithVersionCheck(existing);
        log.info("Kafka 输出定义已修改: outputId={} topic={}", outputId, existing.getTargetTopic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long outputId) {
        require(outputId);
        int refs = outputMapper.countReferences(outputId);
        if (refs > 0) {
            List<String> codes = outputMapper.listReferencingRuleCodes(outputId);
            String listed = codes == null || codes.isEmpty() ? String.valueOf(refs) : String.join("、", codes);
            throw new BusinessException(HttpStatus.CONFLICT, 5063,
                                        "输出定义已被引用，无法删除: " + listed);
        }
        LambdaUpdateWrapper<IotRuleKafkaOutput> wrapper = new LambdaUpdateWrapper<IotRuleKafkaOutput>()
            .setSql("deleted = " + outputId)
            .eq(IotRuleKafkaOutput::getOutputId, outputId)
            .eq(IotRuleKafkaOutput::getDeleted, 0L);
        if (outputMapper.update(null, wrapper) <= 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2040, "数据已被他人修改，请刷新后重试");
        }
        log.info("Kafka 输出定义已删除: outputId={}", outputId);
    }

    @Override
    public Map<Long, KafkaOutputVO> requireAll(Collection<Long> outputIds, KafkaOutputPurpose purpose) {
        if (outputIds == null || outputIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = outputIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<IotRuleKafkaOutput> rows = outputMapper.selectBatchIds(distinct);
        if (rows.size() != distinct.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400, "Kafka 输出定义不存在或已删除");
        }
        Map<Long, KafkaOutputVO> result = new LinkedHashMap<>();
        for (IotRuleKafkaOutput row : rows) {
            if (purpose != null && !purpose.name().equals(row.getPurpose())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 400,
                                            "输出定义用途必须为 " + purpose.name() + ": " + row.getOutputCode());
            }
            result.put(row.getOutputId(), toVo(row));
        }
        return result;
    }

    @Override
    public List<KafkaOutputVO> listByIds(Collection<Long> outputIds) {
        if (outputIds == null || outputIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = outputIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        Map<Long, IotRuleKafkaOutput> byId = new LinkedHashMap<>();
        for (IotRuleKafkaOutput row : outputMapper.selectBatchIds(distinct)) {
            byId.put(row.getOutputId(), row);
        }
        List<KafkaOutputVO> list = new ArrayList<>();
        for (Long id : outputIds) {
            IotRuleKafkaOutput row = byId.get(id);
            if (row != null) {
                list.add(toVo(row, false));
            }
        }
        return list;
    }

    /**
     * 保存期五步校验（见接口 Javadoc），失败抛 {@code BusinessException} 并在 message 中带 Topic 名。
     */
    private void validateTopic(String targetTopic) {
        String syntax = KafkaTopicInspector.syntaxViolation(targetTopic);
        if (syntax != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400, "targetTopic: " + syntax);
        }
        if (props.getForbiddenTopics() != null && props.getForbiddenTopics().contains(targetTopic)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400,
                                        "targetTopic: Topic 属于内部禁止集合: " + targetTopic);
        }
        boolean allowed = props.getAllowedTopicPrefixes() != null
            && props.getAllowedTopicPrefixes().stream().anyMatch(targetTopic::startsWith);
        if (!allowed) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400,
                                        "targetTopic: Topic 不在允许的前缀范围内: " + targetTopic);
        }
        if (!topicInspector.exists(targetTopic)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 400,
                                        "targetTopic: 目标 Topic 不存在: " + targetTopic);
        }
    }

    private void assertCodeUnique(String outputCode, Long excludeId) {
        LambdaQueryWrapper<IotRuleKafkaOutput> wrapper = new LambdaQueryWrapper<IotRuleKafkaOutput>()
            .eq(IotRuleKafkaOutput::getOutputCode, outputCode);
        if (excludeId != null) {
            wrapper.ne(IotRuleKafkaOutput::getOutputId, excludeId);
        }
        Long count = outputMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 400, "输出编码已存在: " + outputCode);
        }
    }

    private void assertTopicUnique(String targetTopic, Long excludeId) {
        LambdaQueryWrapper<IotRuleKafkaOutput> wrapper = new LambdaQueryWrapper<IotRuleKafkaOutput>()
            .eq(IotRuleKafkaOutput::getTargetTopic, targetTopic);
        if (excludeId != null) {
            wrapper.ne(IotRuleKafkaOutput::getOutputId, excludeId);
        }
        Long count = outputMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 400, "目标 Topic 已被登记: " + targetTopic);
        }
    }

    private IotRuleKafkaOutput require(Long outputId) {
        IotRuleKafkaOutput entity = outputMapper.selectById(outputId);
        if (entity == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 400, "Kafka 输出定义不存在: " + outputId);
        }
        return entity;
    }

    private KafkaOutputVO toVo(IotRuleKafkaOutput entity) {
        return toVo(entity, true);
    }

    private KafkaOutputVO toVo(IotRuleKafkaOutput entity, boolean countReferences) {
        KafkaOutputVO vo = new KafkaOutputVO();
        vo.setOutputId(entity.getOutputId());
        vo.setOutputCode(entity.getOutputCode());
        vo.setOutputName(entity.getOutputName());
        vo.setPurpose(entity.getPurpose());
        vo.setTargetTopic(entity.getTargetTopic());
        vo.setFormat(entity.getFormat());
        vo.setReferenceCount(countReferences ? outputMapper.countReferences(entity.getOutputId()) : 0);
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setVersion(entity.getVersion());
        return vo;
    }
}

package com.unisence.iot.admin.rule.service;

import com.unisence.iot.admin.rule.dto.KafkaOutputQuery;
import com.unisence.iot.admin.rule.dto.KafkaOutputSaveRequest;
import com.unisence.iot.admin.rule.vo.KafkaOutputVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.rule.config.KafkaOutputPurpose;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Kafka 输出定义管理。
 *
 * <h2>保存期校验顺序（任一失败即拒绝并定位到 {@code targetTopic}）</h2>
 * <ol>
 *   <li>名称语法：{@code [a-zA-Z0-9._-]{1,249}}，且不是 {@code .} / {@code ..}；</li>
 *   <li>不在 {@code app.admin.rule-output.forbidden-topics}；</li>
 *   <li>以 {@code app.admin.rule-output.allowed-topic-prefixes} 之一开头；</li>
 *   <li>{@code KafkaTopicInspector.exists(topic)} 为真；</li>
 *   <li>{@code (target_topic, deleted)} 与 {@code (output_code, deleted)} 唯一。</li>
 * </ol>
 *
 * <p>业务错误码：{@code 5061} Kafka 元数据不可用；{@code 5062} 已被引用只允许改名称；{@code 5063} 已被引用禁止删除。
 */
public interface IotRuleKafkaOutputService {

    PageResult<KafkaOutputVO> page(PageRequest<KafkaOutputQuery> request);

    KafkaOutputVO get(Long outputId);

    Long create(KafkaOutputSaveRequest request);

    /**
     * 引用计数非零时只接受 {@code outputName} 变化。
     */
    void update(Long outputId, KafkaOutputSaveRequest request);

    /**
     * 引用计数非零时拒绝，message 列出引用方规则编码。
     */
    void delete(Long outputId);

    /**
     * 供规则/路由保存事务批量校验：全部存在、未删除且用途一致；返回按 {@code outputId} 索引的 VO。
     *
     * @throws com.unisence.iot.common.exception.BusinessException 任一 ID 不存在、已删除或用途不符
     */
    Map<Long, KafkaOutputVO> requireAll(Collection<Long> outputIds, KafkaOutputPurpose purpose);

    /**
     * 供档位 / 路由详情回填。
     */
    List<KafkaOutputVO> listByIds(Collection<Long> outputIds);
}

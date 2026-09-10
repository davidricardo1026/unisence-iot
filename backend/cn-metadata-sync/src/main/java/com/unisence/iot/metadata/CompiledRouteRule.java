package com.unisence.iot.metadata;

import com.unisence.iot.message.type.MessageType;

import java.util.List;
import java.util.Set;

/**
 * 透传路由规则的规则级视图（{@code RuleKind.ROUTE}，运行期身份 {@code R:ruleId}）。
 *
 * <p>没有脚本、档位与状态，因此不实现 {@link CompiledRule}，也不进入即时/窗口索引。
 * 供元数据状态页与日志使用；热路径读取的是按 {@code (productKey, messageType)} 预合并后的 {@link RouteTarget} 列表。
 *
 * @param ruleId      规则 ID（与即时/窗口规则不共享 ID 空间）
 * @param ruleCode    规则业务编码
 * @param messageType {@code PROPERTY} 或 {@code EVENT}
 * @param productIds  绑定的普通产品 ID
 * @param targets     绑定的输出目标，按 {@code outputId ASC}，非空
 */
public record CompiledRouteRule(
    long ruleId,
    String ruleCode,
    MessageType messageType,
    Set<Long> productIds,
    List<KafkaOutputTarget> targets) {

    public CompiledRouteRule {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("透传路由规则必须至少绑定一个 Kafka 输出: ruleId=" + ruleId);
        }
        productIds = Set.copyOf(productIds);
        targets = List.copyOf(targets);
    }
}

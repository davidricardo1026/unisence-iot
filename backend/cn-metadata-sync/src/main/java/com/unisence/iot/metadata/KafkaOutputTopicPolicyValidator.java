package com.unisence.iot.metadata;

import com.unisence.iot.rule.config.RuleKind;

import java.util.List;
import java.util.Set;

/**
 * Kafka 输出目标的命名空间策略校验：目标 Topic 必须以任一允许前缀开头，且不在禁止集合
 * （输入 Topic、显式 state Topic、业务 DLQ）内。
 *
 * <p>纯字符串判断，零 Kafka 依赖。Topic 存在性由管理端在保存期用 Kafka Admin 校验，本类不重复。
 * 两侧数据面各自装配一个实例：engine 只看透传目标，rule-stream 只看档位目标。
 * 前缀与禁止集合三份配置必须逐项一致，本类只在配置漂移时才会真正拒绝。
 */
public final class KafkaOutputTopicPolicyValidator implements MetadataCandidateValidator {

    private final List<String> allowedPrefixes;
    private final Set<String> forbiddenTopics;
    private final Scope scope;

    private KafkaOutputTopicPolicyValidator(List<String> allowedPrefixes, Set<String> forbiddenTopics, Scope scope) {
        if (allowedPrefixes == null || allowedPrefixes.isEmpty()) {
            throw new IllegalArgumentException("allowedPrefixes 不能为空");
        }
        this.allowedPrefixes = List.copyOf(allowedPrefixes);
        this.forbiddenTopics = Set.copyOf(forbiddenTopics);
        this.scope = scope;
    }

    /**
     * engine 侧：只校验候选 {@link RuleSnapshot} 中全部 {@link RouteTarget#targetTopic()}。
     */
    public static KafkaOutputTopicPolicyValidator forRoutes(List<String> allowedPrefixes,
                                                            Set<String> forbiddenTopics) {
        return new KafkaOutputTopicPolicyValidator(allowedPrefixes, forbiddenTopics, Scope.ROUTES);
    }

    /**
     * rule-stream 侧：只校验候选 {@link RuleSnapshot} 中全部 {@code CompiledLevel.targets()} 的 Topic。
     */
    public static KafkaOutputTopicPolicyValidator forLevels(List<String> allowedPrefixes,
                                                            Set<String> forbiddenTopics) {
        return new KafkaOutputTopicPolicyValidator(allowedPrefixes, forbiddenTopics, Scope.LEVELS);
    }

    /**
     * 遍历本实例作用域内的全部目标 Topic；首个违规即抛出，携带 ruleKind / ruleId / outputId / topic / reason。
     */
    @Override
    public void validate(EngineMetadataSnapshot candidate) throws MetadataCandidateRejectedException {
        switch (scope) {
            case LEVELS -> validateLevels(candidate);
            case ROUTES -> validateRoutes(candidate);
        }
    }

    private void validateLevels(EngineMetadataSnapshot candidate) {
        for (CompiledRule rule : candidate.rules().byKey().values()) {
            for (CompiledLevel level : rule.levels()) {
                for (KafkaOutputTarget target : level.targets()) {
                    rejectIfViolated(rule.kind(), rule.ruleId(), target);
                }
            }
        }
    }

    private void validateRoutes(EngineMetadataSnapshot candidate) {
        for (CompiledRouteRule route : candidate.rules().routesById().values()) {
            for (KafkaOutputTarget target : route.targets()) {
                rejectIfViolated(RuleKind.ROUTE, route.ruleId(), target);
            }
        }
    }

    private void rejectIfViolated(RuleKind kind, long ruleId, KafkaOutputTarget target) {
        String reason = violationOf(target.targetTopic());
        if (reason != null) {
            throw new MetadataCandidateRejectedException(
                kind, ruleId, target.outputId(), target.targetTopic(), reason);
        }
    }

    /**
     * 单个 Topic 是否符合策略。供 {@link #validate} 内部调用，也供启动期配置自检复用。
     *
     * @return {@code null} 表示合规，否则返回拒绝原因（如 {@code "not in allowed prefixes"} / {@code "forbidden topic"}）
     */
    public String violationOf(String topic) {
        if (topic != null && forbiddenTopics.contains(topic)) {
            return "forbidden topic";
        }
        if (topic != null) {
            for (String prefix : allowedPrefixes) {
                if (topic.startsWith(prefix)) {
                    return null;
                }
            }
        }
        return "not in allowed prefixes";
    }

    private enum Scope {
        ROUTES,
        LEVELS
    }
}

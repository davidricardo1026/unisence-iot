package com.unisence.iot.metadata;

import com.unisence.iot.rule.config.RuleKind;

/**
 * 候选元数据根被 {@link MetadataCandidateValidator} 拒绝。
 *
 * <p>五个定位字段必须原样进入日志与 {@code APPLY_FAILED} 状态上报，供管理端展示。
 */
public final class MetadataCandidateRejectedException extends RuntimeException {

    private final RuleKind ruleKind;
    private final long ruleId;
    private final long outputId;
    private final String topic;
    private final String reason;

    public MetadataCandidateRejectedException(RuleKind ruleKind, long ruleId, long outputId,
                                              String topic, String reason) {
        super("候选元数据被拒绝: ruleKind=" + ruleKind + " ruleId=" + ruleId
                  + " outputId=" + outputId + " topic=" + topic + " reason=" + reason);
        this.ruleKind = ruleKind;
        this.ruleId = ruleId;
        this.outputId = outputId;
        this.topic = topic;
        this.reason = reason;
    }

    public RuleKind ruleKind() {
        return ruleKind;
    }

    public long ruleId() {
        return ruleId;
    }

    public long outputId() {
        return outputId;
    }

    public String topic() {
        return topic;
    }

    public String reason() {
        return reason;
    }
}

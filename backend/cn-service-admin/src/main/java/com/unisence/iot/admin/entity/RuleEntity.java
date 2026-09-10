package com.unisence.iot.admin.entity;

/**
 * 两张规则表的公共列视图。
 *
 * <p>{@code us_iot_rule_instant} 与 {@code us_iot_rule_window} 只差
 * {@code window_config} / {@code aggregate_config} 两列，其余十余列完全一致
 * （schema-engine.sql 的建模原则 1）。保存事务里那部分是逐字相同的赋值，
 * 靠这个接口共用一份，避免两处各写一遍后慢慢分叉。
 *
 * <p><b>它只覆盖公共列，不试图抽象差异列</b>：窗口与聚合配置的读写留在各自的分支里，
 * 由 {@code RuleKind} 分派 —— 把差异也塞进接口就会重新出现「一半实现抛不支持」的形态，
 * 正是本次重设计要消除的东西。
 *
 * <p>getter/setter 由实体上的 Lombok {@code @Data} 生成，此处只声明契约。
 */
public interface RuleEntity {

    Long getRuleId();

    void setRuleId(Long ruleId);

    String getRuleCode();

    void setRuleCode(String ruleCode);

    String getRuleName();

    void setRuleName(String ruleName);

    String getMessageType();

    void setMessageType(String messageType);

    String getListenerConfig();

    void setListenerConfig(String listenerConfig);

    String getFilterScript();

    void setFilterScript(String filterScript);

    String getScriptSha256();

    void setScriptSha256(String scriptSha256);

    String getCompileResult();

    void setCompileResult(String compileResult);

    String getErrorPolicy();

    void setErrorPolicy(String errorPolicy);

    Integer getStatus();

    void setStatus(Integer status);

    Long getRevision();

    void setRevision(Long revision);

    Integer getVersion();

    void setVersion(Integer version);

    java.time.LocalDateTime getCreateTime();

    java.time.LocalDateTime getUpdateTime();
}

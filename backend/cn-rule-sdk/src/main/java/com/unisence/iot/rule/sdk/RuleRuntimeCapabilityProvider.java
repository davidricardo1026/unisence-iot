package com.unisence.iot.rule.sdk;

/**
 * 由可选规则运行时通过 ServiceLoader 声明能力。
 * 管理端与执行端都以实际 classpath 为准，不使用可伪造的布尔配置。
 */
public interface RuleRuntimeCapabilityProvider {

    boolean supportsWindowRules();
}

package com.unisence.iot.admin.rule.kafka;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@code app.admin.rule-output.*} 绑定。
 */
@Configuration
@EnableConfigurationProperties(AdminRuleOutputProperties.class)
public class RuleOutputConfig {
}

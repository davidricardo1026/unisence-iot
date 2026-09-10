package com.unisence.iot.admin.rule;

import com.unisence.iot.rule.compiler.RuleScriptCompiler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 规则脚本编译器装配。
 *
 * <p>编译器<b>全局单例</b>：它内部持有两个 {@code GroovyClassLoader}，每次编译产生的
 * {@code Class} 都挂在其上。每次请求新建一个编译器会让 Metaspace 随请求量持续增长，
 * 且丢掉 SDK 内部的编译缓存。
 */
@Configuration
@EnableConfigurationProperties({RuleScriptProperties.class, RuleWindowProperties.class})
public class RuleScriptConfig {

    @Bean(destroyMethod = "close")
    public RuleScriptCompiler ruleScriptCompiler(RuleScriptProperties properties) {
        return new RuleScriptCompiler(properties.toLimits());
    }
}

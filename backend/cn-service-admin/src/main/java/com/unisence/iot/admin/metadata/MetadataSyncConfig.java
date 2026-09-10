package com.unisence.iot.admin.metadata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 元数据总线 admin 侧装配（metadata-sync-bus.md §十二）。
 *
 * <p>{@code @EnableScheduling} 在此开启，供 {@link MetadataChangeCleaner} 的变更目录清理使用。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MetadataSyncProperties.class)
public class MetadataSyncConfig {

    /**
     * 启动期即校验配置，越界直接拒绝启动。
     *
     * <p>清理批次或保留期配错不会立刻表现出来 —— 要等到某个 engine 失联几天后才暴露成
     * 「日志缺口 → 全量重建」，那时已经无法定位到是配置问题。必须炸在启动期。
     */
    @Bean
    public MetadataSyncPropertiesValidator metadataSyncPropertiesValidator(MetadataSyncProperties properties) {
        properties.validate();
        return new MetadataSyncPropertiesValidator();
    }

    /**
     * 仅作为「校验已执行」的标记 bean。
     */
    public static final class MetadataSyncPropertiesValidator {
    }
}

package com.unisence.iot.admin.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    public StorageService localStorageService(StorageProperties props) {
        return new LocalStorageAdapter(props);
    }

    // 后续接入 MinIO 时在这里追加：
    // @Bean
    // @ConditionalOnProperty(name = "app.storage.type", havingValue = "minio")
    // public StorageService minioStorageService(...) { ... }
}

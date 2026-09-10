package com.unisence.iot.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.async")
public class AsyncProperties {

    private PoolProperties normal = new PoolProperties();
    private PoolProperties important = new PoolProperties();

    @Data
    public static class PoolProperties {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 1024;
        private String threadNamePrefix = "async-";
    }
}

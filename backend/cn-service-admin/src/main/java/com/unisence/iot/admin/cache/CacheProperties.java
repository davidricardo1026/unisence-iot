package com.unisence.iot.admin.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private String keyPrefix = "unisence:cache";

    private Evict evict = new Evict();

    @Data
    public static class Evict {
        private String channel = "unisence:cache:evict";
    }
}

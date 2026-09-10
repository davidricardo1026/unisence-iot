package com.unisence.iot.admin.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /**
     * 默认本地文件系统；后续可切换至 MinIO。
     */
    private String type = "local";

    private Local local = new Local();

    @Data
    public static class Local {
        private String basePath = "/data/uploads";
    }
}

package com.unisence.iot.init.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.init")
public class InitProperties {
    private boolean enabled = true;

    private Greptime greptime = new Greptime();

    /**
     * 根部门名称 (如: 发展管委会)
     */
    private String rootDeptName = "发展管委会";

    @Data
    public static class Greptime {
        private boolean enabled = true;
        private String jdbcUrl;
        private String user = "";
        private String password = "";
    }
}

package com.unisence.iot.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityPasswordConfig {

    /**
     * Bcrypt 核心加密器
     * 设置工作因子（Cost）为 10，计算耗时约 80-100ms，人为拉长单次计算时间，完美阻断针对数据库脱库后的离线暴力破解
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}

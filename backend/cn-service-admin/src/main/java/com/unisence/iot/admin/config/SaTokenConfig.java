package com.unisence.iot.admin.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注：由于 server.servlet.context-path 已配置为 /api，
        // Spring MVC 拦截器匹配的是 servlet path，其已被容器剔除掉了 /api 前缀。
        // 因此，拦截路径应配置为除去 /api 后的实际 Mapping。
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
            .addPathPatterns("/**")
            .excludePathPatterns(
                "/auth/login",
                "/auth/enabled-types",
                // 图片通过 <img> 直接请求，无法附带 Authorization 请求头；文件内容使用稳定公开媒体地址。
                "/system/files/*/content"
            );
    }
}

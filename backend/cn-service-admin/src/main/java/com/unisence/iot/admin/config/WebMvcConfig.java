package com.unisence.iot.admin.config;

import com.unisence.iot.admin.storage.StorageProperties;
import com.unisence.iot.common.api.PageRequestArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final StorageProperties storageProperties;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageRequestArgumentResolver());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String basePath = storageProperties.getLocal().getBasePath();
        if (!basePath.endsWith("/")) {
            basePath = basePath + "/";
        }
        registry.addResourceHandler("/files/**")
            .addResourceLocations("file:" + basePath);
    }
}

package com.unisence.iot.admin.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@RequiredArgsConstructor
@EnableConfigurationProperties(AsyncProperties.class)
public class AsyncConfig {

    public static final String NORMAL_EXECUTOR = "normalExecutor";
    public static final String IMPORTANT_EXECUTOR = "importantExecutor";

    private final AsyncProperties props;

    /**
     * 普通线程池 — 非关键异步任务（操作日志等）
     * 满载时直接丢弃，不阻塞业务线程
     */
    @Bean(NORMAL_EXECUTOR)
    public Executor normalExecutor() {
        AsyncProperties.PoolProperties p = props.getNormal();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(p.getCorePoolSize());
        executor.setMaxPoolSize(p.getMaxPoolSize());
        executor.setQueueCapacity(p.getQueueCapacity());
        executor.setThreadNamePrefix(p.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 重要线程池 — 关键异步任务（缓存清理等）
     * 满载时由调用方线程执行，保证任务不丢失
     */
    @Bean(IMPORTANT_EXECUTOR)
    public Executor importantExecutor() {
        AsyncProperties.PoolProperties p = props.getImportant();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(p.getCorePoolSize());
        executor.setMaxPoolSize(p.getMaxPoolSize());
        executor.setQueueCapacity(p.getQueueCapacity());
        executor.setThreadNamePrefix(p.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

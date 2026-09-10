package com.unisence.iot.driver.common.config;

import com.unisence.iot.driver.common.kafka.DriverKafkaProperties;
import com.unisence.iot.message.codec.MessageCodec;
import com.unisence.iot.message.codec.MessagePackMessageCodec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "com.unisence.iot.driver.common")
@EnableConfigurationProperties(DriverKafkaProperties.class)
public class DriverCommonAutoConfiguration {

    /**
     * 上行消息编解码器。无状态、线程安全，单例即可。
     *
     * <p>{@code @ConditionalOnMissingBean} 让驱动可以替换实现（如接入遗留 JSON 报文的过渡编解码），
     * 但默认必须是 MessagePack —— 编码格式是跨模块契约，不允许各驱动各自为政。
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageCodec messageCodec() {
        return new MessagePackMessageCodec();
    }
}

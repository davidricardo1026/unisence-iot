package com.unisence.iot.admin.config;

import com.unisence.iot.common.crypto.AesGcmFieldEncryptor;
import com.unisence.iot.common.crypto.FieldCryptoProperties;
import com.unisence.iot.common.crypto.FieldEncryptor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
public class FieldCryptoConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.crypto.field")
    public FieldCryptoProperties fieldCryptoProperties() {
        return new FieldCryptoProperties();
    }

    @Bean
    public FieldEncryptor fieldEncryptor(FieldCryptoProperties properties) {
        return new AesGcmFieldEncryptor(properties);
    }
}

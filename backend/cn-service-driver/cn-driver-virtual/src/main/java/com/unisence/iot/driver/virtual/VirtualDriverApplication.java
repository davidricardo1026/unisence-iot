package com.unisence.iot.driver.virtual;

import com.unisence.iot.driver.virtual.config.VirtualDriverProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(VirtualDriverProperties.class)
public class VirtualDriverApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirtualDriverApplication.class, args);
    }
}

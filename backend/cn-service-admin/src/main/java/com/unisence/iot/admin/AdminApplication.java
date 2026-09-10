package com.unisence.iot.admin;

import com.unisence.iot.common.exception.HttpApiExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

@SpringBootApplication()
@EnableDiscoveryClient
@Import(HttpApiExceptionHandler.class)
@MapperScan("com.unisence.iot.admin.mapper")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}

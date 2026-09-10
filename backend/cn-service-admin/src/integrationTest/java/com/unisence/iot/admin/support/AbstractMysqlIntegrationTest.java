package com.unisence.iot.admin.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试基类：提供一个整个测试 JVM 共享的临时 MySQL 容器（单例，退出时由 Testcontainers Ryuk 清理）。
 * <p>
 * 容器启动时用建表脚本 {@code schema/schema-system.sql} 初始化全部 {@code us_sys_*} 表
 * （该脚本由 build.gradle 的 processTestResources 从 {@code app/backend/sql/} 拷入 test classpath）。
 * 子类继承即可获得指向该容器的 {@code spring.datasource}。
 * <p>
 * 镜像 {@code mysql:8.0.45} 与线上一致；本机已有该镜像时 Testcontainers 直接复用、不重复拉取。
 */
public abstract class AbstractMysqlIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.45")
        .withInitScript("schema/schema-system.sql");

    static {
        // 单例容器：整个测试 JVM 只起一次，所有 IT 子类共用
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
}

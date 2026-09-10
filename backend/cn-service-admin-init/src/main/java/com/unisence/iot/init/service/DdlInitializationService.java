package com.unisence.iot.init.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

/**
 * 只面向全新空 schema 的一次性 MySQL DDL 初始化器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DdlInitializationService {

    private static final List<String> MYSQL_SCHEMAS = List.of(
        "schema/schema-system.sql",
        "schema/schema-device.sql",
        "schema/schema-engine.sql"
    );

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public void initializeEmptyDatabase() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
            String.class
        );
        if (!tables.isEmpty()) {
            throw new IllegalStateException(
                "拒绝初始化非空数据库：当前 schema 已存在 " + tables.size() + " 张表，首张表为 " + tables.get(0));
        }

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(false);
        for (String schema : MYSQL_SCHEMAS) {
            populator.addScript(new ClassPathResource(schema));
        }
        log.info("Target database is empty; executing {} MySQL schema scripts", MYSQL_SCHEMAS.size());
        populator.execute(dataSource);
    }
}

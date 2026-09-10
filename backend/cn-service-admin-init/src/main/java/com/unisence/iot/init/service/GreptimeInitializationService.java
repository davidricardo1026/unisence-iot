package com.unisence.iot.init.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.init.config.InitProperties;
import com.unisence.iot.rule.sdk.EventDataRetention;
import com.unisence.iot.rule.sdk.PropertyDataType;
import com.unisence.iot.timeseries.EventColumnTypes;
import com.unisence.iot.timeseries.EventTableNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * GreptimeDB 一次性拓荒：固定表执行静态 DDL，事件分表从种子产品定义生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GreptimeInitializationService {

    private final InitProperties initProperties;
    private final ObjectMapper objectMapper;

    public void initialize() {
        InitProperties.Greptime properties = initProperties.getGreptime();
        if (!properties.isEnabled()) {
            log.info("GreptimeDB initialization is disabled.");
            return;
        }
        if (properties.getJdbcUrl() == null || properties.getJdbcUrl().isBlank()) {
            throw new IllegalStateException("app.init.greptime.jdbc-url 必填");
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getUser());
        dataSource.setPassword(properties.getPassword());

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.addScript(new ClassPathResource("schema/schema-device-greptime.sql"));
        String eventDdl = eventTableDdl(readProducts());
        if (!eventDdl.isBlank()) {
            populator.addScript(new ByteArrayResource(eventDdl.getBytes(StandardCharsets.UTF_8)));
        }
        log.info("Initializing GreptimeDB fixed tables and virtual-driver event tables...");
        populator.execute(dataSource);
    }

    private List<Map<String, Object>> readProducts() {
        try (InputStream input = new ClassPathResource("data/products.json").getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("读取虚拟驱动产品种子失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String eventTableDdl(List<Map<String, Object>> products) {
        StringBuilder ddl = new StringBuilder(512);
        for (Map<String, Object> product : products) {
            String productKey = String.valueOf(product.get("productKey"));
            Object rawEvents = product.get("events");
            if (!(rawEvents instanceof List<?> events)) {
                continue;
            }
            for (Object rawEvent : events) {
                if (!(rawEvent instanceof Map<?, ?> event)) {
                    throw new IllegalArgumentException("事件种子格式非法");
                }
                String identifier = String.valueOf(event.get("identifier"));
                boolean ttlEnabled = Boolean.TRUE.equals(event.get("ttlEnabled"));
                Integer ttlValue = ttlEnabled ? ((Number) event.get("ttlValue")).intValue() : null;
                String ttlUnit = ttlEnabled ? String.valueOf(event.get("ttlUnit")) : null;
                EventDataRetention.requireValid(ttlEnabled, ttlValue, ttlUnit);
                String table = EventTableNames.physicalTable(productKey, identifier);
                ddl.append("CREATE TABLE IF NOT EXISTS `").append(table).append("` (\n")
                    .append("  `product_key` STRING,\n")
                    .append("  `device_code` STRING,\n")
                    .append("  `event_type` INT32,\n")
                    .append("  `msg_id` STRING");
                Object rawParams = event.get("inputParams");
                if (rawParams instanceof List<?> params) {
                    for (Object rawParam : params) {
                        Map<String, Object> param = (Map<String, Object>) rawParam;
                        String paramIdentifier = String.valueOf(param.get("identifier"));
                        PropertyDataType dataType = PropertyDataType.fromCode(String.valueOf(param.get("dataType")));
                        ddl.append(",\n  `").append(EventTableNames.columnName(paramIdentifier)).append("` ")
                            .append(EventColumnTypes.greptimeSqlType(dataType));
                    }
                }
                ddl.append(",\n  `time` TIMESTAMP(3) NOT NULL,\n")
                    .append("  TIME INDEX (`time`),\n")
                    .append("  PRIMARY KEY (`product_key`, `device_code`)\n")
                    .append(") WITH (");
                if (ttlEnabled) {
                    ddl.append("'ttl' = '").append(EventDataRetention.greptimeTtl(ttlValue, ttlUnit))
                        .append("', ");
                }
                ddl.append("'append_mode' = 'true');\n\n");
            }
        }
        return ddl.toString();
    }
}

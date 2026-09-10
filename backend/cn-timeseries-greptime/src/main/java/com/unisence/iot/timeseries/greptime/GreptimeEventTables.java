package com.unisence.iot.timeseries.greptime;

import com.unisence.iot.rule.sdk.EventDataRetention;
import com.unisence.iot.timeseries.*;

import java.sql.*;
import java.util.*;

/**
 * 事件表 DDL：无表则 CREATE，有表则参数列差集先 DROP 再 ADD。禁止 MODIFY / RENAME，禁止 DROP 固定 TAG。
 */
final class GreptimeEventTables {

    private GreptimeEventTables() {
    }

    static void ensure(Connection connection, EventTableSpec spec, boolean applyTtlChange) throws SQLException {
        String table = spec.physicalTable();
        Map<String, String> desired = desiredParamTypes(spec);
        if (!tableExists(connection, table)) {
            execute(connection, createSql(table, spec));
            return;
        }
        Map<String, String> existing = paramColumns(connection, table);
        for (Map.Entry<String, String> column : desired.entrySet()) {
            String current = existing.get(column.getKey());
            if (current == null) {
                continue;
            }
            if (!current.equals(column.getValue())) {
                throw new EventColumnTypeConflictException(
                    spec.productKey(), spec.identifier(), column.getKey().substring(2),
                    current, column.getValue());
            }
        }
        List<String> drops = new ArrayList<>();
        for (String column : existing.keySet()) {
            if (!desired.containsKey(column)) {
                drops.add(column);
            }
        }
        List<String> adds = new ArrayList<>();
        Map<String, String> addTypes = new LinkedHashMap<>();
        for (Map.Entry<String, String> column : desired.entrySet()) {
            if (!existing.containsKey(column.getKey())) {
                adds.add(column.getKey());
                addTypes.put(column.getKey(), column.getValue());
            }
        }
        for (String column : drops) {
            execute(connection, "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(column));
        }
        for (String column : adds) {
            execute(connection, "ALTER TABLE " + quote(table) + " ADD COLUMN " + quote(column)
                + " " + addTypes.get(column));
        }
        if (applyTtlChange) {
            execute(connection, ttlAlterSql(table, spec));
        }
    }

    static String quote(String identifier) {
        if (identifier == null || !identifier.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("非法时序标识符: " + identifier);
        }
        return "`" + identifier + "`";
    }

    private static Map<String, String> desiredParamTypes(EventTableSpec spec) {
        Map<String, String> types = new LinkedHashMap<>();
        for (EventTableColumn column : spec.columns()) {
            types.put(EventTableNames.columnName(column.paramIdentifier()),
                      EventColumnTypes.greptimeSqlType(column.dataType()));
        }
        return types;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        String sql = """
            SELECT 1 FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = ?
             LIMIT 1
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException ignored) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SHOW TABLES")) {
                while (result.next()) {
                    if (table.equalsIgnoreCase(result.getString(1))) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    private static Map<String, String> paramColumns(Connection connection, String table) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        String sql = """
            SELECT column_name, data_type FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    putParamColumn(columns, result.getString(1), result.getString(2));
                }
            }
            if (!columns.isEmpty() || tableExists(connection, table)) {
                return columns;
            }
        } catch (SQLException ignored) {
            columns.clear();
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("DESC TABLE " + quote(table))) {
            while (result.next()) {
                putParamColumn(columns, result.getString(1), result.getString(2));
            }
        }
        return columns;
    }

    private static void putParamColumn(Map<String, String> columns, String name, String type) {
        if (name == null) {
            return;
        }
        String column = name.toLowerCase(Locale.ROOT);
        if (!column.startsWith("p_")) {
            return;
        }
        columns.put(column, EventColumnTypes.normalizeSqlType(type));
    }

    private static String createSql(String table, EventTableSpec spec) {
        StringBuilder sql = new StringBuilder(256);
        sql.append("CREATE TABLE IF NOT EXISTS ").append(quote(table)).append(" (\n")
            .append("    `product_key` STRING,\n")
            .append("    `device_code` STRING,\n")
            .append("    `event_type` INT32,\n")
            .append("    `msg_id` STRING");
        for (EventTableColumn column : spec.columns()) {
            sql.append(",\n    ").append(quote(EventTableNames.columnName(column.paramIdentifier())))
                .append(' ').append(EventColumnTypes.greptimeSqlType(column.dataType()));
        }
        sql.append(",\n    `time` TIMESTAMP(3) NOT NULL,\n")
            .append("    TIME INDEX (`time`),\n")
            .append("    PRIMARY KEY (`product_key`, `device_code`)\n")
            .append(") WITH (");
        if (spec.ttlEnabled()) {
            sql.append("'ttl' = '")
                .append(EventDataRetention.greptimeTtl(spec.ttlValue(), spec.ttlUnit()))
                .append("', ");
        }
        sql.append("'append_mode' = 'true')");
        return sql.toString();
    }

    private static String ttlAlterSql(String table, EventTableSpec spec) {
        if (!spec.ttlEnabled()) {
            return "ALTER TABLE " + quote(table) + " UNSET 'ttl'";
        }
        return "ALTER TABLE " + quote(table) + " SET 'ttl'='"
            + EventDataRetention.greptimeTtl(spec.ttlValue(), spec.ttlUnit()) + "'";
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new EventTableProvisionException("GreptimeDB DDL 失败: " + sql, e);
        }
    }
}

package com.unisence.iot.timeseries.greptime;

import com.unisence.iot.rule.sdk.PropertyDataType;
import com.unisence.iot.timeseries.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.greptime.GreptimeDB;
import io.greptime.WriteOp;
import io.greptime.models.*;
import io.greptime.rpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

final class GreptimeTimeSeriesClient implements TimeSeriesClient {
    private static final String ONLINE_TABLE = "device_online_log";

    private static final TableSchema ONLINE_SCHEMA = TableSchema.newBuilder(ONLINE_TABLE)
        .addTag("product_key", DataType.String)
        .addTag("device_code", DataType.String)
        .addField("event", DataType.Int32)
        .addField("reason", DataType.String)
        .addTimestamp("time", DataType.TimestampMillisecond)
        .build();

    private final GreptimeDB ingester;
    private final HikariConfig queryConfig;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile HikariDataSource queryPool;
    private final Context writeContext = Context.newDefault().withHint("auto_create_table", "false");
    private volatile TimeSeriesObserver observer = TimeSeriesObserver.NOOP;

    GreptimeTimeSeriesClient(GreptimeDB ingester, HikariConfig queryConfig) {
        this.ingester = ingester;
        this.queryConfig = queryConfig;
    }

    @Override
    public void observer(TimeSeriesObserver observer) {
        this.observer = observer == null ? TimeSeriesObserver.NOOP : observer;
    }

    @Override
    public void writeProperties(List<PropertyLog> rows) {
        if (rows.isEmpty()) return;
        Map<PropertyRoute, List<PropertyLog>> grouped = new LinkedHashMap<>();
        for (PropertyLog row : rows) {
            PropertyRoute route = new PropertyRoute(row.valueType(), row.retentionDays());
            grouped.computeIfAbsent(route, ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<PropertyRoute, List<PropertyLog>> entry : grouped.entrySet()) {
            PropertyRoute route = entry.getKey();
            String tableName = PropertyTableNames.physicalTable(route.valueType(), route.retentionDays());
            Table table = Table.from(propertySchema(tableName, route.valueType()));
            for (PropertyLog row : entry.getValue()) {
                table.addRow(row.productKey(), row.deviceCode(), row.identifier(), row.msgId(),
                             propertyWriteValue(route.valueType(), row.value()), row.occurredAt());
            }
            write(tableName, table.complete(), entry.getValue().size());
        }
    }

    @Override
    public void ensureEventTable(EventTableSpec spec, boolean applyTtlChange) {
        try (Connection connection = queries().getConnection()) {
            GreptimeEventTables.ensure(connection, spec, applyTtlChange);
        } catch (EventColumnTypeConflictException | EventTableProvisionException e) {
            throw e;
        } catch (SQLException e) {
            throw new EventTableProvisionException(
                "事件时序表供给失败: productKey=" + spec.productKey() + " identifier=" + spec.identifier(), e);
        }
    }

    @Override
    public void writeEvents(List<DeviceEventWrite> rows) {
        if (rows.isEmpty()) return;
        Map<String, List<DeviceEventWrite>> grouped = new LinkedHashMap<>();
        for (DeviceEventWrite row : rows) {
            String table = EventTableNames.physicalTable(row.productKey(), row.identifier());
            grouped.computeIfAbsent(table, key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<DeviceEventWrite>> entry : grouped.entrySet()) {
            writeEventTable(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void writeOnlineLogs(List<DeviceOnlineLog> rows) {
        if (rows.isEmpty()) return;
        Table table = Table.from(ONLINE_SCHEMA);
        for (DeviceOnlineLog row : rows) {
            table.addRow(row.productKey(), row.deviceCode(), row.event(), row.reason(), row.occurredAt());
        }
        write(ONLINE_TABLE, table.complete(), rows.size());
    }

    private void writeEventTable(String tableName, List<DeviceEventWrite> rows) {
        LinkedHashMap<String, PropertyDataType> params = new LinkedHashMap<>();
        for (DeviceEventWrite row : rows) {
            for (EventParamValue param : row.params()) {
                params.putIfAbsent(param.identifier(), param.dataType());
            }
        }
        var builder = TableSchema.newBuilder(tableName)
            .addTag("product_key", DataType.String)
            .addTag("device_code", DataType.String)
            .addField("event_type", DataType.Int32)
            .addField("msg_id", DataType.String);
        for (Map.Entry<String, PropertyDataType> param : params.entrySet()) {
            builder.addField(EventTableNames.columnName(param.getKey()), writeType(param.getValue()));
        }
        TableSchema schema = builder.addTimestamp("time", DataType.TimestampMillisecond).build();
        Table table = Table.from(schema);
        for (DeviceEventWrite row : rows) {
            Object[] values = new Object[5 + params.size()];
            values[0] = row.productKey();
            values[1] = row.deviceCode();
            values[2] = row.eventType();
            values[3] = row.msgId();
            int index = 4;
            for (Map.Entry<String, PropertyDataType> param : params.entrySet()) {
                values[index++] = paramValue(row, param.getKey(), param.getValue());
            }
            values[index] = row.occurredAt();
            table.addRow(values);
        }
        write(tableName, table.complete(), rows.size());
    }

    @Override
    public List<PropertyValue> latestProperties(String productKey, String deviceCode) {
        Map<String, PropertyValue> latest = new LinkedHashMap<>();
        for (PropertyTableNames.Table table : PropertyTableNames.all()) {
            String sql = "SELECT identifier, `value`, msg_id, time FROM ("
                + "SELECT identifier, `value`, msg_id, time, "
                + "ROW_NUMBER() OVER (PARTITION BY identifier ORDER BY time DESC, msg_id DESC) AS row_num FROM "
                + table.name() + " WHERE product_key = ? AND device_code = ?) latest WHERE row_num = 1";
            for (PropertyValue value : query(sql, statement -> {
                statement.setString(1, productKey);
                statement.setString(2, deviceCode);
            }, result -> propertyValue(result, table.valueType(), true))) {
                latest.merge(value.identifier(), value,
                             (left, right) -> right.occurredAt() > left.occurredAt() ? right : left);
            }
        }
        return latest.values().stream().sorted(java.util.Comparator.comparing(PropertyValue::identifier)).toList();
    }

    @Override
    public List<PropertyHistoryPoint> propertyHistory(String productKey, String deviceCode, String identifier,
                                                      String dataType, int retentionDays,
                                                      long from, long to, long bucketMillis) {
        int valueType = PropertyDataType.fromCode(dataType).valueType();
        String table = PropertyTableNames.physicalTable(valueType, retentionDays);
        String value = valueColumn(dataType);
        String interval = bucketMillis + " milliseconds";
        String sql = """
            WITH points AS (
                SELECT time, %s AS value, date_bin('%s'::INTERVAL, time) AS bucket
                  FROM %s
                 WHERE product_key = ? AND device_code = ? AND identifier = ?
                   AND time >= ? AND time < ? AND %s IS NOT NULL
            ), extrema AS (
                SELECT bucket, MIN(value) AS min_value, MAX(value) AS max_value
                  FROM points GROUP BY bucket
            )
            SELECT e.bucket,
                   MIN(CASE WHEN p.value = e.min_value THEN p.time END) AS min_time,
                   e.min_value,
                   MIN(CASE WHEN p.value = e.max_value THEN p.time END) AS max_time,
                   e.max_value
              FROM extrema e JOIN points p ON p.bucket = e.bucket
             GROUP BY e.bucket, e.min_value, e.max_value
             ORDER BY e.bucket
            """.formatted(value, interval, table, value);
        return query(sql, statement -> {
            statement.setString(1, productKey);
            statement.setString(2, deviceCode);
            statement.setString(3, identifier);
            statement.setTimestamp(4, timestamp(from));
            statement.setTimestamp(5, timestamp(to));
        }, result -> {
            List<PropertyHistoryPoint> points = new ArrayList<>(2);
            long minTime = epochMillis(result, 2);
            long maxTime = epochMillis(result, 4);
            Number min = number(result, 3, dataType);
            Number max = number(result, 5, dataType);
            if (minTime <= maxTime) {
                points.add(new PropertyHistoryPoint(minTime, min));
                if (maxTime != minTime) points.add(new PropertyHistoryPoint(maxTime, max));
            } else {
                points.add(new PropertyHistoryPoint(maxTime, max));
                points.add(new PropertyHistoryPoint(minTime, min));
            }
            return points;
        }).stream().flatMap(List::stream).toList();
    }

    @Override
    public TimeSeriesPage<PropertyValue> rawProperties(String productKey, String deviceCode, String identifier,
                                                       String dataType, int retentionDays,
                                                       long from, long to, long offset, int limit) {
        int valueType = PropertyDataType.fromCode(dataType).valueType();
        String table = PropertyTableNames.physicalTable(valueType, retentionDays);
        String where = "product_key = ? AND device_code = ? AND identifier = ? AND time >= ? AND time < ?";
        Binder binder = statement -> bindPropertyRange(statement, productKey, deviceCode, identifier, from, to);
        long total = count(table, where, binder);
        if (offset >= total) return new TimeSeriesPage<>(List.of(), total);
        String sql = "SELECT time, `value`, msg_id FROM " + table
            + " WHERE " + where + " ORDER BY time DESC, msg_id DESC LIMIT ? OFFSET ?";
        List<PropertyValue> values = query(sql, statement -> {
            binder.bind(statement);
            statement.setInt(6, limit);
            statement.setLong(7, offset);
        }, result -> propertyValue(result, valueType, false));
        return new TimeSeriesPage<>(values, total);
    }

    @Override
    public TimeSeriesPage<DeviceEventRow> events(String productKey, String deviceCode, String identifier,
                                                 long from, long to, long offset, int limit) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("事件查询 identifier 必填");
        }
        String table = GreptimeEventTables.quote(EventTableNames.physicalTable(productKey, identifier));
        String where = "product_key = ? AND device_code = ? AND time >= ? AND time < ?";
        Binder binder = statement -> bindRange(statement, productKey, deviceCode, from, to);
        long total = count(table, where, binder);
        if (offset >= total) return new TimeSeriesPage<>(List.of(), total);
        String sql = "SELECT * FROM " + table + " WHERE " + where + " ORDER BY time DESC, msg_id DESC LIMIT ? OFFSET ?";
        List<DeviceEventRow> values = query(sql, statement -> {
            binder.bind(statement);
            statement.setInt(5, limit);
            statement.setLong(6, offset);
        }, result -> eventRow(productKey, deviceCode, identifier, result));
        return new TimeSeriesPage<>(values, total);
    }

    @Override
    public long countEvents(String productKey, String identifier, long from, long to) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("事件查询 identifier 必填");
        }
        String table = GreptimeEventTables.quote(EventTableNames.physicalTable(productKey, identifier));
        return count(table, "product_key = ? AND time >= ? AND time < ?", statement -> {
            statement.setString(1, productKey);
            statement.setTimestamp(2, timestamp(from));
            statement.setTimestamp(3, timestamp(to));
        });
    }

    @Override
    public TimeSeriesPage<DeviceOnlineLog> onlineLogs(String productKey, String deviceCode,
                                                      long from, long to, long offset, int limit) {
        String where = "product_key = ? AND device_code = ? AND time >= ? AND time < ?";
        Binder binder = statement -> bindRange(statement, productKey, deviceCode, from, to);
        long total = count(ONLINE_TABLE, where, binder);
        if (offset >= total) return new TimeSeriesPage<>(List.of(), total);
        String sql = "SELECT time, event, reason FROM " + ONLINE_TABLE + " WHERE " + where
            + " ORDER BY time DESC LIMIT ? OFFSET ?";
        List<DeviceOnlineLog> values = query(sql, statement -> {
            binder.bind(statement);
            statement.setInt(5, limit);
            statement.setLong(6, offset);
        }, result -> online(productKey, deviceCode, result));
        return new TimeSeriesPage<>(values, total);
    }

    @Override
    public List<DeviceOnlineLog> onlineHistory(String productKey, String deviceCode, long from, long to, int limit) {
        String sql = "SELECT time, event, reason FROM " + ONLINE_TABLE
            + " WHERE product_key = ? AND device_code = ? AND time >= ? AND time < ? ORDER BY time ASC LIMIT ?";
        return query(sql, statement -> {
            bindRange(statement, productKey, deviceCode, from, to);
            statement.setInt(5, limit);
        }, result -> online(productKey, deviceCode, result));
    }

    @Override
    public Integer previousOnlineEvent(String productKey, String deviceCode, long before) {
        String sql = "SELECT event FROM " + ONLINE_TABLE
            + " WHERE product_key = ? AND device_code = ? AND time < ? ORDER BY time DESC LIMIT 1";
        List<Integer> values = query(sql, statement -> {
            statement.setString(1, productKey);
            statement.setString(2, deviceCode);
            statement.setTimestamp(3, timestamp(before));
        }, result -> result.getInt(1));
        return values.isEmpty() ? null : values.getFirst();
    }

    private void write(String tableName, Table table, int rows) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            Result<WriteOk, Err> result = ingester.write(List.of(table), WriteOp.Insert, writeContext).get();
            if (!result.isOk()) {
                Throwable cause = result.getErr().getError();
                boolean retryable = retryable(cause);
                outcome = retryable ? "retryable" : "rejected";
                throw new TimeSeriesStorageException(retryable, "GreptimeDB 拒绝写入: " + result.getErr(), cause);
            }
            WriteOk ok = result.getOk();
            if (ok.getFailure() > 0) {
                outcome = "rejected";
                throw new TimeSeriesStorageException(false, "GreptimeDB 部分写入失败: " + ok, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "retryable";
            throw new TimeSeriesStorageException(true, "等待 GreptimeDB 写入被中断", e);
        } catch (ExecutionException e) {
            boolean retryable = retryable(e.getCause());
            outcome = retryable ? "retryable" : "rejected";
            throw new TimeSeriesStorageException(retryable, "GreptimeDB 写入失败", e.getCause());
        } catch (TimeSeriesStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            boolean retryable = retryable(e);
            outcome = retryable ? "retryable" : "rejected";
            throw new TimeSeriesStorageException(retryable, "GreptimeDB 写入失败", e);
        } finally {
            observer.onWrite(tableName, (System.nanoTime() - start) / 1_000_000L, rows, outcome);
        }
    }

    private long count(String table, String where, Binder binder) {
        List<Long> values = query("SELECT COUNT(*) FROM " + table + " WHERE " + where,
                                  binder,
                                  result -> result.getLong(1));
        return values.isEmpty() ? 0 : values.getFirst();
    }

    private <T> List<T> query(String sql, Binder binder, Mapper<T> mapper) {
        try (Connection connection = queries().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<T> values = new ArrayList<>();
                while (result.next()) values.add(mapper.map(result));
                return values;
            }
        } catch (SQLException e) {
            throw new TimeSeriesStorageException(sqlRetryable(e), "GreptimeDB 查询失败", e);
        }
    }

    private HikariDataSource queries() {
        HikariDataSource current = queryPool;
        if (current != null) return current;
        synchronized (this) {
            if (closed.get()) throw new IllegalStateException("GreptimeDB 客户端已关闭");
            if (queryPool == null) queryPool = new HikariDataSource(queryConfig);
            return queryPool;
        }
    }

    private static DeviceEventRow eventRow(String productKey, String deviceCode, String identifier, ResultSet result)
        throws SQLException {
        ResultSetMetaData meta = result.getMetaData();
        int eventType = 0;
        String msgId = null;
        long occurredAt = 0;
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String column = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
            switch (column) {
                case "product_key", "device_code" -> {
                }
                case "event_type" -> eventType = result.getInt(i);
                case "msg_id" -> msgId = result.getString(i);
                case "time" -> occurredAt = epochMillis(result, i);
                default -> {
                    if (column.startsWith("p_")) {
                        params.put(column.substring(2), eventParam(result, i));
                    }
                }
            }
        }
        return new DeviceEventRow(productKey, deviceCode, identifier, eventType, msgId, occurredAt, params);
    }

    private static Object eventParam(ResultSet result, int column) throws SQLException {
        Object value = result.getObject(column);
        if (value == null) return null;
        if (value instanceof Boolean || value instanceof String) return value;
        if (value instanceof Number number) {
            if (value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal) {
                return number.doubleValue();
            }
            return number.longValue();
        }
        return value;
    }

    private static Object paramValue(DeviceEventWrite row, String identifier, PropertyDataType dataType) {
        for (EventParamValue param : row.params()) {
            if (param.identifier().equalsIgnoreCase(identifier)) {
                return coerce(dataType, param.value());
            }
        }
        return null;
    }

    private static Object coerce(PropertyDataType dataType, Object value) {
        if (value == null) return null;
        return switch (dataType.valueType()) {
            case PropertyDataType.VALUE_TYPE_BOOL -> value;
            case PropertyDataType.VALUE_TYPE_LONG -> value instanceof Number number ? number.longValue() : value;
            case PropertyDataType.VALUE_TYPE_DOUBLE -> value instanceof Number number ? number.doubleValue() : value;
            default -> value instanceof String ? value : String.valueOf(value);
        };
    }

    private static DataType writeType(PropertyDataType dataType) {
        return switch (dataType.valueType()) {
            case PropertyDataType.VALUE_TYPE_BOOL -> DataType.Bool;
            case PropertyDataType.VALUE_TYPE_LONG -> DataType.Int64;
            case PropertyDataType.VALUE_TYPE_DOUBLE -> DataType.Float64;
            default -> DataType.String;
        };
    }

    private static PropertyValue propertyValue(ResultSet result, int valueType, boolean withIdentifier)
        throws SQLException {
        int valueColumn = 2;
        int msgIdColumn = 3;
        int occurredAtColumn = withIdentifier ? 4 : 1;
        String identifier = withIdentifier ? result.getString(1) : null;
        return new PropertyValue(identifier, propertyReadValue(result, valueColumn, valueType), valueType,
                                 epochMillis(result, occurredAtColumn), result.getString(msgIdColumn));
    }

    private static DeviceOnlineLog online(String productKey, String deviceCode, ResultSet result) throws SQLException {
        return new DeviceOnlineLog(productKey,
                                   deviceCode,
                                   result.getInt(2),
                                   result.getString(3),
                                   epochMillis(result, 1));
    }

    private static long epochMillis(ResultSet result, int column) throws SQLException {
        Object value = result.getObject(column);
        if (value instanceof Timestamp timestamp) return timestamp.getTime();
        if (value instanceof Number number) return number.longValue();
        return Timestamp.valueOf(value.toString()).getTime();
    }

    private static Number number(ResultSet result, int column, String dataType) throws SQLException {
        return switch (dataType) {
            case "bool", "int" -> result.getLong(column);
            case "float", "double" -> result.getDouble(column);
            default -> throw new IllegalArgumentException("不支持曲线的数据类型: " + dataType);
        };
    }

    private static String valueColumn(String dataType) {
        return switch (dataType) {
            case "bool" -> "CAST(`value` AS INT32)";
            case "int", "float", "double" -> "`value`";
            default -> throw new IllegalArgumentException("不支持曲线的数据类型: " + dataType);
        };
    }

    private static TableSchema propertySchema(String tableName, int valueType) {
        return TableSchema.newBuilder(tableName)
            .addTag("product_key", DataType.String)
            .addTag("device_code", DataType.String)
            .addTag("identifier", DataType.String)
            .addField("msg_id", DataType.String)
            .addField("value", propertyWriteType(valueType))
            .addTimestamp("time", DataType.TimestampMillisecond)
            .build();
    }

    private static DataType propertyWriteType(int valueType) {
        return switch (valueType) {
            case PropertyDataType.VALUE_TYPE_BOOL -> DataType.Bool;
            case PropertyDataType.VALUE_TYPE_LONG -> DataType.Int64;
            case PropertyDataType.VALUE_TYPE_DOUBLE -> DataType.Float64;
            case PropertyDataType.VALUE_TYPE_TEXT -> DataType.String;
            default -> throw new IllegalArgumentException("未知属性 valueType: " + valueType);
        };
    }

    private static Object propertyWriteValue(int valueType, Object value) {
        return switch (valueType) {
            case PropertyDataType.VALUE_TYPE_BOOL -> value;
            case PropertyDataType.VALUE_TYPE_LONG -> value instanceof Number number ? number.longValue() : value;
            case PropertyDataType.VALUE_TYPE_DOUBLE -> value instanceof Number number ? number.doubleValue() : value;
            case PropertyDataType.VALUE_TYPE_TEXT -> value instanceof String ? value : String.valueOf(value);
            default -> throw new IllegalArgumentException("未知属性 valueType: " + valueType);
        };
    }

    private static Object propertyReadValue(ResultSet result, int column, int valueType) throws SQLException {
        return switch (valueType) {
            case PropertyDataType.VALUE_TYPE_BOOL -> result.getBoolean(column);
            case PropertyDataType.VALUE_TYPE_LONG -> result.getLong(column);
            case PropertyDataType.VALUE_TYPE_DOUBLE -> result.getDouble(column);
            case PropertyDataType.VALUE_TYPE_TEXT -> result.getString(column);
            default -> throw new IllegalArgumentException("未知属性 valueType: " + valueType);
        };
    }

    private record PropertyRoute(int valueType, int retentionDays) {
        private PropertyRoute {
            PropertyTableNames.physicalTable(valueType, retentionDays);
        }
    }

    private static void bindPropertyRange(PreparedStatement statement, String productKey, String deviceCode,
                                          String identifier, long from, long to) throws SQLException {
        statement.setString(1, productKey);
        statement.setString(2, deviceCode);
        statement.setString(3, identifier);
        statement.setTimestamp(4, timestamp(from));
        statement.setTimestamp(5, timestamp(to));
    }

    private static void bindRange(PreparedStatement statement, String productKey, String deviceCode,
                                  long from, long to) throws SQLException {
        statement.setString(1, productKey);
        statement.setString(2, deviceCode);
        statement.setTimestamp(3, timestamp(from));
        statement.setTimestamp(4, timestamp(to));
    }

    private static Timestamp timestamp(long epochMillis) {
        return Timestamp.from(Instant.ofEpochMilli(epochMillis));
    }

    private static boolean retryable(Throwable error) {
        if (error instanceof StatusRuntimeException grpc) {
            Status.Code code = grpc.getStatus().getCode();
            return code != Status.Code.INVALID_ARGUMENT && code != Status.Code.FAILED_PRECONDITION
                && code != Status.Code.OUT_OF_RANGE && code != Status.Code.PERMISSION_DENIED
                && code != Status.Code.UNAUTHENTICATED;
        }
        return true;
    }

    private static boolean sqlRetryable(SQLException error) {
        String state = error.getSQLState();
        return state == null || state.startsWith("08") || state.startsWith("40") || state.startsWith("HYT");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HikariDataSource current = queryPool;
        if (current != null) current.close();
        ingester.shutdownGracefully();
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Mapper<T> {
        T map(ResultSet result) throws SQLException;
    }
}

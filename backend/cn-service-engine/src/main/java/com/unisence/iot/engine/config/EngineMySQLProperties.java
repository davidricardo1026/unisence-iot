package com.unisence.iot.engine.config;

/**
 * {@code app.engine.mysql.*}（engine-runtime-io.md §九）。
 *
 * <p>对应 {@code vertx-mysql-client} 的 {@code MySQLConnectOptions} 与 {@code PoolOptions}，
 * <b>不是 JDBC</b>，因此没有 {@code jdbc-url}。
 *
 * @param cachePreparedStatements engine 是少数几条 SQL 的高频重复执行，预编译缓存能免掉每次 PREPARE 往返，
 *                                是本客户端上收益最直接的一项，默认必须开启
 * @param batchSize               {@code executeBatch(List<Tuple>)} 单次 Tuple 数上限
 */
public record EngineMySQLProperties(
    String host,
    int port,
    String database,
    String user,
    String password,
    int poolMaxSize,
    int maxWaitQueueSize,
    int connectionTimeoutMs,
    int idleTimeoutMs,
    boolean cachePreparedStatements,
    int preparedStatementCacheMaxSize,
    int batchSize) {

    public EngineMySQLProperties {
        requirePositive(port, "app.engine.mysql.port");
        requirePositive(poolMaxSize, "app.engine.mysql.pool-max-size");
        requirePositive(connectionTimeoutMs, "app.engine.mysql.connection-timeout-ms");
        requirePositive(idleTimeoutMs, "app.engine.mysql.idle-timeout-ms");
        requirePositive(preparedStatementCacheMaxSize, "app.engine.mysql.prepared-statement-cache-max-size");
        requirePositive(batchSize, "app.engine.mysql.batch-size");
        // maxWaitQueueSize 允许 -1（不限），故不套用 requirePositive
        if (maxWaitQueueSize == 0) {
            throw new IllegalArgumentException("app.engine.mysql.max-wait-queue-size 不可为 0（-1 表示不限）");
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数，当前值: " + value);
        }
    }
}

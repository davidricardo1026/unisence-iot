package com.unisence.iot.engine.config;

import com.unisence.iot.timeseries.TimeSeriesClient;
import com.unisence.iot.timeseries.TimeSeriesClients;
import io.vertx.core.Vertx;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 外部客户端装配（engine-runtime-io.md §三、§四、§九）。
 *
 * <p>取代此前硬编码 {@code localhost} 与 {@code root/root} 的 {@code SysConfig}：
 * 全部连接参数来自 {@code app.engine.*}，口令走环境变量。
 *
 * <p>连接一律池化 —— 每批新建连接意味着每批一次 TCP 握手与认证。
 */
@Slf4j
public final class EngineClients {

    private EngineClients() {
    }

    public static TimeSeriesClient timeSeries(EngineTimeSeriesProperties props) {
        TimeSeriesClient client = TimeSeriesClients.open(props.client());
        log.info("时序数据库客户端已建立: type={} endpoints={} db={} poolMaxSize={} batchMaxRows={} writeConcurrency={}",
                 props.client().type(), props.client().endpoints(), props.client().database(),
                 props.client().poolMaxSize(), props.batchMaxRows(), props.writeConcurrency());
        return client;
    }

    public static Redis redis(Vertx vertx, EngineRedisProperties props) {
        Redis redis = Redis.createClient(vertx, new RedisOptions()
            .setConnectionString(props.connectionString())
            .setMaxPoolSize(props.poolMaxSize())
            .setMaxPoolWaiting(props.maxPoolWaiting())
            .setMaxWaitingHandlers(props.maxWaitingHandlers()));
        log.info("Redis 客户端已建立: poolMaxSize={} maxPoolWaiting={}",
                 props.poolMaxSize(), props.maxPoolWaiting());
        return redis;
    }

    public static Pool mysqlPool(Vertx vertx, EngineMySQLProperties props) {
        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
            .setHost(props.host())
            .setPort(props.port())
            .setDatabase(props.database())
            .setUser(props.user())
            .setPassword(props.password())
            // engine 是少数几条 SQL 的高频重复执行，预编译缓存免掉每次 PREPARE 往返
            .setCachePreparedStatements(props.cachePreparedStatements())
            .setPreparedStatementCacheMaxSize(props.preparedStatementCacheMaxSize());

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(props.poolMaxSize())
            .setMaxWaitQueueSize(props.maxWaitQueueSize())
            .setConnectionTimeout(props.connectionTimeoutMs())
            .setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)
            .setIdleTimeout(props.idleTimeoutMs())
            .setIdleTimeoutUnit(TimeUnit.MILLISECONDS);

        Pool pool = MySQLBuilder.pool()
            .connectingTo(connectOptions)
            .with(poolOptions)
            .using(vertx)
            .build();
        log.info("MySQL 连接池已建立: {}:{}/{} poolMaxSize={} cachePrepared={}",
                 props.host(), props.port(), props.database(),
                 props.poolMaxSize(), props.cachePreparedStatements());
        return pool;
    }
}

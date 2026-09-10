package com.unisence.iot.timeseries.greptime;

import com.unisence.iot.timeseries.TimeSeriesClient;
import com.unisence.iot.timeseries.TimeSeriesConfig;
import com.unisence.iot.timeseries.TimeSeriesProvider;
import com.zaxxer.hikari.HikariConfig;
import io.greptime.GreptimeDB;
import io.greptime.models.AuthInfo;
import io.greptime.options.GreptimeOptions;
import io.greptime.rpc.RpcOptions;

public final class GreptimeTimeSeriesProvider implements TimeSeriesProvider {
    @Override
    public String type() {
        return "greptime";
    }

    @Override
    public TimeSeriesClient open(TimeSeriesConfig config) {
        RpcOptions rpc = RpcOptions.newDefault();
        rpc.setDefaultRpcTimeout(config.intOption("write-timeout-ms", 30_000));
        GreptimeOptions options = GreptimeOptions.newBuilder(config.endpoints().toArray(String[]::new),
                                                             config.database())
            .authInfo(config.user().isBlank() ? AuthInfo.noAuthorization() : new AuthInfo(config.user(),
                                                                                          config.password()))
            .rpcOptions(rpc)
            .writeMaxRetries(config.intOption("write-max-retries", 3))
            .maxInFlightWritePoints(config.intOption("max-in-flight-write-points", 655_360))
            .build();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.option("jdbc-url", defaultJdbcUrl(config)));
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolMaxSize());
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setPoolName("greptime-query");
        // 查询池延迟到首次查询再建立：engine 进程只有写流，不应为未使用的 MySQL 协议查询占连接和线程。
        hikari.setInitializationFailTimeout(-1);
        return new GreptimeTimeSeriesClient(GreptimeDB.create(options), hikari);
    }

    private static String defaultJdbcUrl(TimeSeriesConfig config) {
        String endpoint = config.endpoints().getFirst();
        int colon = endpoint.lastIndexOf(':');
        String host = colon > 0 ? endpoint.substring(0, colon) : endpoint;
        return "jdbc:mysql://" + host + ":4002/" + config.database()
            + "?useSSL=false&serverTimezone=UTC&connectionTimeZone=UTC";
    }
}

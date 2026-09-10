package com.unisence.iot.engine.config;

import com.unisence.iot.timeseries.TimeSeriesConfig;

public record EngineTimeSeriesProperties(
    TimeSeriesConfig client,
    int batchMaxRows,
    int batchMaxBytes,
    int writeConcurrency,
    int schemaSyncConcurrency) {

    public EngineTimeSeriesProperties {
        if (client == null) throw new IllegalArgumentException("app.engine.timeseries 配置不可为空");
        requirePositive(batchMaxRows, "app.engine.timeseries.batch-max-rows");
        requirePositive(batchMaxBytes, "app.engine.timeseries.batch-max-bytes");
        requirePositive(writeConcurrency, "app.engine.timeseries.write-concurrency");
        requirePositive(schemaSyncConcurrency, "app.engine.timeseries.schema-sync-concurrency");
        if (writeConcurrency > client.poolMaxSize()) {
            throw new IllegalArgumentException("app.engine.timeseries.write-concurrency 不得超过 pool-max-size");
        }
    }

    private static void requirePositive(int value, String key) {
        if (value <= 0) throw new IllegalArgumentException(key + " 必须为正数");
    }
}

package com.unisence.iot.timeseries;

import java.util.ServiceLoader;

public final class TimeSeriesClients {
    private TimeSeriesClients() {
    }

    public static TimeSeriesClient open(TimeSeriesConfig config) {
        if ("iotdb".equalsIgnoreCase(config.type())) {
            throw new IllegalStateException("IoTDB 时序实现已移除，请将 app.<服务>.timeseries.type 设为 greptime");
        }
        TimeSeriesProvider match = null;
        for (TimeSeriesProvider provider : ServiceLoader.load(TimeSeriesProvider.class)) {
            if (!provider.type().equalsIgnoreCase(config.type())) continue;
            if (match != null) throw new IllegalStateException("重复的时序数据库实现: " + config.type());
            match = provider;
        }
        if (match == null) throw new IllegalStateException("未安装时序数据库实现: " + config.type());
        return match.open(config);
    }
}

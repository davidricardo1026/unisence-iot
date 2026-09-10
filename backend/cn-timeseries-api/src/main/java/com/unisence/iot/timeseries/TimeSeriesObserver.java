package com.unisence.iot.timeseries;

@FunctionalInterface
public interface TimeSeriesObserver {
    TimeSeriesObserver NOOP = (table, millis, rows, result) -> {
    };

    void onWrite(String table, long millis, int rows, String result);
}

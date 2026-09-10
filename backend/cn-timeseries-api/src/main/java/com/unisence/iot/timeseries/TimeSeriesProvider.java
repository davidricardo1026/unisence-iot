package com.unisence.iot.timeseries;

public interface TimeSeriesProvider {
    String type();

    TimeSeriesClient open(TimeSeriesConfig config);
}

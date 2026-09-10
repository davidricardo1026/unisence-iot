package com.unisence.iot.timeseries;

import java.util.List;

public record TimeSeriesPage<T>(List<T> records, long total) {
    public TimeSeriesPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}

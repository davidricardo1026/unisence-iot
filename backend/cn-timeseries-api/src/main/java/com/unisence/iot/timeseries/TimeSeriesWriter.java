package com.unisence.iot.timeseries;

import java.util.List;

public interface TimeSeriesWriter {
    void writeProperties(List<PropertyLog> rows);

    void writeEvents(List<DeviceEventWrite> rows);

    void writeOnlineLogs(List<DeviceOnlineLog> rows);
}

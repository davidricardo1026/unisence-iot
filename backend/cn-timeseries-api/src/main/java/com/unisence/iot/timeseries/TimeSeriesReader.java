package com.unisence.iot.timeseries;

import java.util.List;

public interface TimeSeriesReader extends AutoCloseable {
    List<PropertyValue> latestProperties(String productKey, String deviceCode);

    List<PropertyHistoryPoint> propertyHistory(String productKey, String deviceCode, String identifier,
                                               String dataType, int retentionDays,
                                               long from, long to, long bucketMillis);

    TimeSeriesPage<PropertyValue> rawProperties(String productKey, String deviceCode, String identifier,
                                                String dataType, int retentionDays,
                                                long from, long to, long offset, int limit);

    TimeSeriesPage<DeviceEventRow> events(String productKey, String deviceCode, String identifier,
                                          long from, long to, long offset, int limit);

    long countEvents(String productKey, String identifier, long from, long to);

    TimeSeriesPage<DeviceOnlineLog> onlineLogs(String productKey, String deviceCode,
                                               long from, long to, long offset, int limit);

    List<DeviceOnlineLog> onlineHistory(String productKey, String deviceCode, long from, long to, int limit);

    Integer previousOnlineEvent(String productKey, String deviceCode, long before);

    @Override
    void close();
}

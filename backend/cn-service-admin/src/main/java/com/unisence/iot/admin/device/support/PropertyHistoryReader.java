package com.unisence.iot.admin.device.support;

import com.unisence.iot.admin.config.AdminTimeSeriesProperties;
import com.unisence.iot.admin.device.vo.PropertyHistoryPointVO;
import com.unisence.iot.admin.device.vo.PropertyHistoryVO;
import com.unisence.iot.timeseries.TimeSeriesReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PropertyHistoryReader {
    private final TimeSeriesReader timeSeries;
    private final AdminTimeSeriesProperties properties;

    public PropertyHistoryVO read(String productKey, String deviceCode, String identifier,
                                  String dataType, int retentionDays, String unit, long from, long to) {
        long bucketMillis = bucketMillis(to - from, properties.getHistoryChartMaxPoints());
        var points = timeSeries.propertyHistory(productKey, deviceCode, identifier, dataType, retentionDays,
                                                from, to, bucketMillis)
            .stream().map(value -> new PropertyHistoryPointVO(value.occurredAt(), value.value())).toList();
        return new PropertyHistoryVO(identifier, dataType, unit, points);
    }

    private static long bucketMillis(long rangeMillis, int maxPoints) {
        long maxBuckets = Math.max(2, maxPoints / 2L);
        long divisor = maxBuckets - 1;
        long quotient = rangeMillis / divisor;
        return Math.max(1, rangeMillis % divisor == 0 ? quotient : quotient + 1);
    }
}

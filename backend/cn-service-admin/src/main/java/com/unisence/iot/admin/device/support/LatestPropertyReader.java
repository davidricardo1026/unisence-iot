package com.unisence.iot.admin.device.support;

import com.unisence.iot.admin.device.vo.LatestPropertySnapshotVO;
import com.unisence.iot.admin.device.vo.LatestPropertyVO;
import com.unisence.iot.timeseries.TimeSeriesReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class LatestPropertyReader {
    private final TimeSeriesReader timeSeries;

    public LatestPropertySnapshotVO read(String productKey, String deviceCode) {
        try {
            List<LatestPropertyVO> values = timeSeries.latestProperties(productKey, deviceCode).stream()
                .map(value -> new LatestPropertyVO(value.identifier(), value.value(), value.valueType(),
                                                   value.occurredAt(), value.msgId()))
                .toList();
            return new LatestPropertySnapshotVO(true, values);
        } catch (Exception error) {
            log.warn("读取设备当前值失败，设备静态详情仍可用: productKey={} deviceCode={} errorClass={}",
                     productKey, deviceCode, error.getClass().getName());
            return new LatestPropertySnapshotVO(false, List.of());
        }
    }
}

package com.unisence.iot.admin.device.support;

import com.unisence.iot.admin.device.vo.DeviceEventVO;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.timeseries.TimeSeriesReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceEventReader {
    private final TimeSeriesReader timeSeries;

    public PageResult<DeviceEventVO> read(String productKey, String deviceCode, String identifier,
                                          long from, long to, int pageNum, int pageSize,
                                          Map<String, String> eventNames) {
        long offset = Math.multiplyExact((long) pageNum - 1, pageSize);
        try {
            var page = timeSeries.events(productKey, deviceCode, identifier, from, to, offset, pageSize);
            return new PageResult<>(page.records().stream().map(value -> new DeviceEventVO(
                value.occurredAt(), value.identifier(),
                eventNames.getOrDefault(value.identifier(), value.identifier()), value.eventType(),
                value.params(), value.msgId())).toList(), page.total());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            log.warn("读取设备事件失败: productKey={} deviceCode={} errorClass={}",
                     productKey, deviceCode, error.getClass().getName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, 3019, "设备事件暂不可用");
        }
    }
}

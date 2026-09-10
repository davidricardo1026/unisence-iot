package com.unisence.iot.admin.device.support;

import com.unisence.iot.admin.device.vo.PropertyRawValueVO;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.timeseries.TimeSeriesReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PropertyRawValueReader {
    private final TimeSeriesReader timeSeries;

    public PageResult<PropertyRawValueVO> read(String productKey, String deviceCode, String identifier,
                                               String dataType, int retentionDays,
                                               long from, long to, int pageNum, int pageSize) {
        long offset = Math.multiplyExact((long) pageNum - 1, pageSize);
        try {
            var page = timeSeries.rawProperties(productKey, deviceCode, identifier, dataType, retentionDays,
                                                from, to, offset, pageSize);
            return new PageResult<>(page.records().stream()
                                        .map(value -> new PropertyRawValueVO(value.occurredAt(),
                                                                             value.value(),
                                                                             value.valueType(),
                                                                             value.msgId()))
                                        .toList(), page.total());
        } catch (Exception error) {
            log.warn("读取属性原始值失败: productKey={} deviceCode={} identifier={} errorClass={}",
                     productKey, deviceCode, identifier, error.getClass().getName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, 3021, "属性原始值暂不可用");
        }
    }
}

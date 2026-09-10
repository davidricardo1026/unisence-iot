package com.unisence.iot.admin.device.support;

import com.unisence.iot.admin.device.vo.DeviceOnlineHistoryVO;
import com.unisence.iot.admin.device.vo.DeviceOnlineLogVO;
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
public class DeviceOnlineLogReader {
    private final TimeSeriesReader timeSeries;

    public PageResult<DeviceOnlineLogVO> read(String productKey, String deviceCode, long from, long to,
                                              int pageNum, int pageSize) {
        long offset = Math.multiplyExact((long) pageNum - 1, pageSize);
        try {
            var page = timeSeries.onlineLogs(productKey, deviceCode, from, to, offset, pageSize);
            return new PageResult<>(page.records().stream().map(DeviceOnlineLogReader::toVo).toList(), page.total());
        } catch (Exception error) {
            log.warn("读取设备上下线历史失败: productKey={} deviceCode={} errorClass={}",
                     productKey, deviceCode, error.getClass().getName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, 3022, "设备上下线历史暂不可用");
        }
    }

    public DeviceOnlineHistoryVO readHistory(String productKey, String deviceCode,
                                             long from, long to, int maxPoints) {
        try {
            Integer initialEvent = timeSeries.previousOnlineEvent(productKey, deviceCode, from);
            var values = timeSeries.onlineHistory(productKey, deviceCode, from, to, maxPoints + 1);
            if (values.size() > maxPoints) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                                            3023,
                                            "所选时间范围内状态跳变点过多，请缩短查询范围");
            }
            return new DeviceOnlineHistoryVO(initialEvent, values.stream().map(DeviceOnlineLogReader::toVo).toList());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            log.warn("读取设备在线状态曲线失败: productKey={} deviceCode={} errorClass={}",
                     productKey, deviceCode, error.getClass().getName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, 3022, "设备上下线历史暂不可用");
        }
    }

    private static DeviceOnlineLogVO toVo(com.unisence.iot.timeseries.DeviceOnlineLog value) {
        return new DeviceOnlineLogVO(value.occurredAt(), value.event(), value.reason());
    }
}

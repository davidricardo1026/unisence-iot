package com.unisence.iot.admin.device.service;

import com.unisence.iot.admin.device.dto.*;
import com.unisence.iot.admin.device.vo.*;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

public interface IotDeviceService {

    PageResult<DeviceVO> pageDevices(PageRequest<DeviceQuery> request);

    DeviceVO getDevice(Long deviceId);

    LatestPropertySnapshotVO getLatestProperties(Long deviceId);

    PropertyHistoryVO getPropertyHistory(Long deviceId, String identifier, long from, long to);

    PageResult<PropertyRawValueVO> pagePropertyRawValues(Long deviceId,
                                                         String identifier,
                                                         PageRequest<PropertyRawQuery> request);

    PageResult<DeviceEventVO> pageDeviceEvents(Long deviceId, PageRequest<DeviceEventQuery> request);

    PageResult<DeviceOnlineLogVO> pageDeviceOnlineLogs(Long deviceId, PageRequest<DeviceOnlineLogQuery> request);

    DeviceOnlineHistoryVO getDeviceOnlineHistory(Long deviceId, long from, long to);

    DeviceCreateVO createDevice(DeviceSaveRequest request);

    void updateDevice(Long deviceId, DeviceSaveRequest request);

    void deleteDevice(Long deviceId);

}

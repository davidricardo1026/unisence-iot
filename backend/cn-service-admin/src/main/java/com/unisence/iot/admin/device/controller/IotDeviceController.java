package com.unisence.iot.admin.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.device.dto.*;
import com.unisence.iot.admin.device.service.IotDeviceService;
import com.unisence.iot.admin.device.vo.*;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/iot/devices")
@RequiredArgsConstructor
public class IotDeviceController {

    private final IotDeviceService deviceService;

    @GetMapping
    @SaCheckPermission("iot:device:list")
    public PageResult<DeviceVO> pageDevices(PageRequest<DeviceQuery> request) {
        return deviceService.pageDevices(request);
    }

    @GetMapping("/{deviceId}")
    @SaCheckPermission("iot:device:list")
    public DeviceVO getDevice(@PathVariable Long deviceId) {
        return deviceService.getDevice(deviceId);
    }

    @GetMapping("/{deviceId}/latest-properties")
    @SaCheckPermission("iot:device:list")
    public LatestPropertySnapshotVO getLatestProperties(@PathVariable Long deviceId) {
        return deviceService.getLatestProperties(deviceId);
    }

    @GetMapping("/{deviceId}/properties/{identifier}/history")
    @SaCheckPermission("iot:device:list")
    public PropertyHistoryVO getPropertyHistory(@PathVariable Long deviceId,
                                                @PathVariable String identifier,
                                                @RequestParam long from,
                                                @RequestParam long to) {
        return deviceService.getPropertyHistory(deviceId, identifier, from, to);
    }

    @GetMapping("/{deviceId}/properties/{identifier}/values")
    @SaCheckPermission("iot:device:list")
    public PageResult<PropertyRawValueVO> pagePropertyRawValues(@PathVariable Long deviceId,
                                                                @PathVariable String identifier,
                                                                PageRequest<PropertyRawQuery> request) {
        return deviceService.pagePropertyRawValues(deviceId, identifier, request);
    }

    @GetMapping("/{deviceId}/events")
    @SaCheckPermission("iot:device:list")
    public PageResult<DeviceEventVO> pageDeviceEvents(@PathVariable Long deviceId,
                                                      PageRequest<DeviceEventQuery> request) {
        return deviceService.pageDeviceEvents(deviceId, request);
    }

    @GetMapping("/{deviceId}/online-logs")
    @SaCheckPermission("iot:device:list")
    public PageResult<DeviceOnlineLogVO> pageDeviceOnlineLogs(@PathVariable Long deviceId,
                                                              PageRequest<DeviceOnlineLogQuery> request) {
        return deviceService.pageDeviceOnlineLogs(deviceId, request);
    }

    @GetMapping("/{deviceId}/online-history")
    @SaCheckPermission("iot:device:list")
    public DeviceOnlineHistoryVO getDeviceOnlineHistory(@PathVariable Long deviceId,
                                                        @RequestParam long from,
                                                        @RequestParam long to) {
        return deviceService.getDeviceOnlineHistory(deviceId, from, to);
    }

    @PostMapping
    @OperLog(title = "设备管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:device:add")
    public DeviceCreateVO createDevice(@RequestBody @Valid DeviceSaveRequest request) {
        return deviceService.createDevice(request);
    }

    @PutMapping("/{deviceId}")
    @OperLog(title = "设备管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:device:edit")
    public void updateDevice(@PathVariable Long deviceId, @RequestBody @Valid DeviceSaveRequest request) {
        deviceService.updateDevice(deviceId, request);
    }

    @DeleteMapping("/{deviceId}")
    @OperLog(title = "设备管理", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:device:remove")
    public void deleteDevice(@PathVariable Long deviceId) {
        deviceService.deleteDevice(deviceId);
    }

}

package com.unisence.iot.admin.device.vo;

import java.util.List;

/**
 * 设备在线状态阶梯图数据。
 *
 * @param initialEvent 查询开始时的状态，1-在线、2-离线；无更早记录时为空
 * @param points       查询区间内按时间升序排列的状态跳变点
 */
public record DeviceOnlineHistoryVO(Integer initialEvent, List<DeviceOnlineLogVO> points) {
}

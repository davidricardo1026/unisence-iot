package com.unisence.iot.admin.device.vo;

import java.util.List;

public record PropertyHistoryVO(
    String identifier,
    String dataType,
    String unit,
    List<PropertyHistoryPointVO> points) {

    public PropertyHistoryVO {
        points = points == null ? List.of() : List.copyOf(points);
    }
}

package com.unisence.iot.admin.device.vo;

import java.util.List;

public record LatestPropertySnapshotVO(boolean available, List<LatestPropertyVO> properties) {

    public LatestPropertySnapshotVO {
        properties = properties == null ? List.of() : List.copyOf(properties);
    }
}

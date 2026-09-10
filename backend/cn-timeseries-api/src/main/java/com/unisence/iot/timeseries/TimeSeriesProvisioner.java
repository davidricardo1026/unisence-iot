package com.unisence.iot.timeseries;

/**
 * 事件时序表供给。仅允许 Admin 保存/克隆事件与 engine {@code EventSchemaSync} 调用。
 */
public interface TimeSeriesProvisioner {

    /**
     * @param applyTtlChange 已有表是否应用 TTL 变更；新建表始终按 spec 创建
     */
    void ensureEventTable(EventTableSpec spec, boolean applyTtlChange);
}

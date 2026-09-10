package com.unisence.iot.engine.storage;

import com.unisence.iot.engine.config.EngineTimeSeriesProperties;
import com.unisence.iot.engine.online.OnlineTransition;
import com.unisence.iot.engine.repository.EngineWriteException;
import com.unisence.iot.timeseries.DeviceOnlineLog;
import com.unisence.iot.timeseries.TimeSeriesStorageException;
import com.unisence.iot.timeseries.TimeSeriesWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下线历史批量落盘。
 */
public class OnlineLogWriter implements AutoCloseable {
    private final TimeSeriesWriter client;
    private final int batchMaxRows;
    private final int batchMaxBytes;

    public OnlineLogWriter(TimeSeriesWriter client, EngineTimeSeriesProperties props) {
        this.client = client;
        this.batchMaxRows = props.batchMaxRows();
        this.batchMaxBytes = props.batchMaxBytes();
    }

    public synchronized void write(List<OnlineTransition> transitions) {
        List<DeviceOnlineLog> rows = new ArrayList<>(Math.min(batchMaxRows, transitions.size()));
        int bytes = 0;
        for (OnlineTransition transition : transitions) {
            if (!transition.writesOnlineLog()) continue;
            DeviceOnlineLog row = new DeviceOnlineLog(transition.productKey(),
                                                      transition.deviceCode(),
                                                      transition.onlineLogEvent(),
                                                      transition.reason(),
                                                      transition.changedAtMs());
            int rowBytes = Long.BYTES + Integer.BYTES + utf8(row.productKey()) + utf8(row.deviceCode()) + utf8(row.reason());
            if (!rows.isEmpty() && (rows.size() >= batchMaxRows || bytes + rowBytes > batchMaxBytes)) {
                writeBatch(rows);
                rows.clear();
                bytes = 0;
            }
            rows.add(row);
            bytes += rowBytes;
        }
        writeBatch(rows);
    }

    private void writeBatch(List<DeviceOnlineLog> rows) {
        if (rows.isEmpty()) return;
        try {
            client.writeOnlineLogs(List.copyOf(rows));
        } catch (TimeSeriesStorageException error) {
            throw new EngineWriteException(error.retryable(), error.getMessage(), error);
        }
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.length() * 3 + Integer.BYTES;
    }

    @Override
    public void close() { }
}

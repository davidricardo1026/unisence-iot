package com.unisence.iot.engine.storage;

import com.unisence.iot.engine.config.EngineTimeSeriesProperties;
import com.unisence.iot.engine.repository.EngineWriteException;
import com.unisence.iot.engine.verticle.VerticleQuiesce;
import com.unisence.iot.message.DeviceEventMessage;
import com.unisence.iot.timeseries.DeviceEventWrite;
import com.unisence.iot.timeseries.EventParamValue;
import com.unisence.iot.timeseries.TimeSeriesStorageException;
import com.unisence.iot.timeseries.TimeSeriesWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 物模型事件实例批量落盘。禁止建表、禁止探活、禁止计数。
 */
public class EventLogWriter implements AutoCloseable {
    public record EventRow(DeviceEventMessage message, String identifier, int eventType, List<EventParamValue> params) {
        public EventRow {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    private final TimeSeriesWriter client;
    private final int batchMaxRows;
    private final int batchMaxBytes;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
        runnable -> Thread.ofPlatform().name("timeseries-event-write").daemon(false).unstarted(runnable));

    public EventLogWriter(TimeSeriesWriter client, EngineTimeSeriesProperties props) {
        this.client = client;
        this.batchMaxRows = props.batchMaxRows();
        this.batchMaxBytes = props.batchMaxBytes();
    }

    public void write(List<EventRow> rows) {
        if (rows.isEmpty()) return;
        try {
            executor.submit(() -> writeOnPoolThread(rows)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineWriteException(true, "等待时序事件写入被中断", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof EngineWriteException value) throw value;
            throw new EngineWriteException(true, "时序事件写入失败", e.getCause());
        }
    }

    private void writeOnPoolThread(List<EventRow> rows) {
        List<DeviceEventWrite> batch = new ArrayList<>(Math.min(batchMaxRows, rows.size()));
        int bytes = 0;
        for (EventRow row : rows) {
            DeviceEventWrite record = toRecord(row);
            int rowBytes = estimatedBytes(record);
            if (!batch.isEmpty() && (batch.size() >= batchMaxRows || bytes + rowBytes > batchMaxBytes)) {
                writeBatch(batch);
                batch.clear();
                bytes = 0;
            }
            batch.add(record);
            bytes += rowBytes;
        }
        writeBatch(batch);
    }

    private void writeBatch(List<DeviceEventWrite> batch) {
        if (batch.isEmpty()) return;
        try {
            client.writeEvents(List.copyOf(batch));
        } catch (TimeSeriesStorageException error) {
            throw new EngineWriteException(error.retryable(), error.getMessage(), error);
        }
    }

    private static DeviceEventWrite toRecord(EventRow row) {
        DeviceEventMessage message = row.message();
        return new DeviceEventWrite(message.productKey(), message.deviceCode(), row.identifier(), row.eventType(),
                                    message.msgId(), message.occurredAt(), row.params());
    }

    private static int estimatedBytes(DeviceEventWrite row) {
        int bytes = Long.BYTES + Integer.BYTES + utf8(row.productKey()) + utf8(row.deviceCode())
            + utf8(row.identifier()) + utf8(row.msgId());
        for (EventParamValue param : row.params()) {
            bytes += utf8(param.identifier());
            Object value = param.value();
            bytes += value == null ? 0 : String.valueOf(value).length() * 3;
        }
        return bytes;
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.length() * 3 + Integer.BYTES;
    }

    @Override
    public void close() {
        VerticleQuiesce.shutdown("时序事件写入池", executor);
    }
}

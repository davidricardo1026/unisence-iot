package com.unisence.iot.engine.storage;

import com.unisence.iot.engine.config.EngineTimeSeriesProperties;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.repository.EngineWriteException;
import com.unisence.iot.engine.repository.PropertyPoint;
import com.unisence.iot.engine.verticle.VerticleQuiesce;
import com.unisence.iot.timeseries.PropertyLog;
import com.unisence.iot.timeseries.TimeSeriesStorageException;
import com.unisence.iot.timeseries.TimeSeriesWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 属性历史的有界并发批量写入门面。
 */
public class PropertyLogWriter implements AutoCloseable {
    private final TimeSeriesWriter client;
    private final ExecutorService executor;
    private final int chunkRows;
    private final int chunkBytes;
    private final EngineMetrics metrics;

    public PropertyLogWriter(TimeSeriesWriter client, EngineTimeSeriesProperties props, EngineMetrics metrics) {
        this.client = client;
        this.chunkRows = props.batchMaxRows();
        this.chunkBytes = props.batchMaxBytes();
        this.metrics = metrics;
        this.executor = Executors.newFixedThreadPool(props.writeConcurrency(),
                                                     platformFactory("timeseries-property-write"));
    }

    public void write(List<PropertyPoint> points) {
        if (points.isEmpty()) return;
        List<Future<?>> futures = new ArrayList<>();
        List<PropertyPoint> chunk = new ArrayList<>(Math.min(chunkRows, points.size()));
        int bytes = 0;
        for (PropertyPoint point : points) {
            int rowBytes = estimatedBytes(point);
            if (!chunk.isEmpty() && (chunk.size() >= chunkRows || bytes + rowBytes > chunkBytes)) {
                List<PropertyPoint> submitted = List.copyOf(chunk);
                submit(futures, submitted);
                chunk.clear();
                bytes = 0;
            }
            chunk.add(point);
            bytes += rowBytes;
        }
        if (!chunk.isEmpty()) {
            List<PropertyPoint> submitted = List.copyOf(chunk);
            submit(futures, submitted);
        }
        awaitAll(futures);
    }

    private void submit(List<Future<?>> futures, List<PropertyPoint> chunk) {
        long submittedAt = System.nanoTime();
        futures.add(executor.submit(() -> {
            if (metrics != null) metrics.timeSeriesWriterWaitMillis((System.nanoTime() - submittedAt) / 1_000_000L);
            writeChunk(chunk);
        }));
    }

    private static int estimatedBytes(PropertyPoint point) {
        int fixed = Long.BYTES + Integer.BYTES + Long.BYTES + Double.BYTES + 1;
        return fixed + utf8WorstCase(point.productKey()) + utf8WorstCase(point.deviceCode())
            + utf8WorstCase(point.identifier()) + utf8WorstCase(point.msgId()) + utf8WorstCase(point.valueText());
    }

    private static int utf8WorstCase(String value) {
        return value == null ? 0 : Math.multiplyExact(value.length(), 3) + Integer.BYTES;
    }

    private void writeChunk(List<PropertyPoint> points) {
        List<PropertyLog> rows = points.stream().map(point -> new PropertyLog(
            point.productKey(),
            point.deviceCode(),
            point.identifier(),
            point.valueType(),
            point.retentionDays(),
            value(point),
            point.msgId(), point.timestampMs())).toList();
        try {
            client.writeProperties(rows);
        } catch (TimeSeriesStorageException error) {
            throw new EngineWriteException(error.retryable(), error.getMessage(), error);
        }
    }

    private static Object value(PropertyPoint point) {
        return switch (point.valueType()) {
            case PropertyPoint.TYPE_BOOL -> point.valueBool();
            case PropertyPoint.TYPE_LONG -> point.valueLong();
            case PropertyPoint.TYPE_DOUBLE -> point.valueDouble();
            case PropertyPoint.TYPE_TEXT -> point.valueText();
            default -> throw new EngineWriteException(false, "未知的 value_type: " + point.valueType(), null);
        };
    }

    private static ThreadFactory platformFactory(String prefix) {
        AtomicInteger slot = new AtomicInteger();
        return runnable -> Thread.ofPlatform().name(prefix + "-" + slot.getAndIncrement())
            .daemon(false).unstarted(runnable);
    }

    private static void awaitAll(List<Future<?>> futures) {
        EngineWriteException failure = null;
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineWriteException(true, "等待时序数据分片写入被中断", e);
            } catch (ExecutionException e) {
                EngineWriteException current = e.getCause() instanceof EngineWriteException value
                    ? value : new EngineWriteException(true, "时序数据分片写入失败", e.getCause());
                if (failure == null || (!failure.retryable() && current.retryable())) failure = current;
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        VerticleQuiesce.shutdown("时序属性写入池", executor);
    }
}

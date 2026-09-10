package com.unisence.iot.engine.storage;

import com.unisence.iot.engine.config.EngineTimeSeriesProperties;
import com.unisence.iot.engine.verticle.VerticleQuiesce;
import com.unisence.iot.metadata.EngineMetadataSnapshot;
import com.unisence.iot.rule.sdk.EventDefinition;
import com.unisence.iot.rule.sdk.EventParamDefinition;
import com.unisence.iot.rule.sdk.ThingModelSnapshot;
import com.unisence.iot.timeseries.EventTableColumn;
import com.unisence.iot.timeseries.EventTableSpec;
import com.unisence.iot.timeseries.TimeSeriesProvisioner;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 元数据快照替换成功后，按权威清单对普通产品事件补跑 {@code ensureEventTable}。不在摄入路径调用。
 */
@Slf4j
public final class EventSchemaSync implements AutoCloseable {

    private final TimeSeriesProvisioner provisioner;
    private final ExecutorService executor;

    public EventSchemaSync(TimeSeriesProvisioner provisioner, EngineTimeSeriesProperties props) {
        this.provisioner = provisioner;
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                return Thread.ofPlatform()
                    .name("event-schema-sync-" + seq.incrementAndGet())
                    .daemon(false)
                    .unstarted(runnable);
            }
        };
        this.executor = Executors.newFixedThreadPool(props.schemaSyncConcurrency(), factory);
    }

    public void sync(EngineMetadataSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Map.Entry<String, ThingModelSnapshot> entry : snapshot.thingModelsByProductKey().entrySet()) {
            String productKey = entry.getKey();
            for (EventDefinition event : entry.getValue().events().values()) {
                EventTableSpec spec;
                try {
                    spec = toSpec(productKey, event);
                } catch (RuntimeException e) {
                    log.warn("跳过非法事件表规格: productKey={} identifier={} err={}",
                             productKey, event.identifier(), e.toString());
                    continue;
                }
                try {
                    executor.execute(() -> ensure(spec));
                } catch (RejectedExecutionException e) {
                    log.warn("事件表反熵任务被拒绝: table={}", spec.physicalTable());
                }
            }
        }
    }

    private void ensure(EventTableSpec spec) {
        try {
            provisioner.ensureEventTable(spec, false);
        } catch (RuntimeException e) {
            log.warn("事件表反熵失败: table={} err={}", spec.physicalTable(), e.toString());
        }
    }

    private static EventTableSpec toSpec(String productKey, EventDefinition event) {
        List<EventTableColumn> columns = new ArrayList<>(event.inputParams().size());
        for (EventParamDefinition param : event.inputParams()) {
            columns.add(new EventTableColumn(param.identifier(), param.dataType()));
        }
        return new EventTableSpec(productKey, event.canonicalIdentifier(), columns,
                                  event.ttlEnabled(), event.ttlValue(), event.ttlUnit());
    }

    @Override
    public void close() {
        VerticleQuiesce.shutdown("事件表反熵池", executor);
    }
}

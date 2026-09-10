package com.unisence.iot.admin.config;

import com.unisence.iot.timeseries.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@EnableConfigurationProperties(AdminTimeSeriesProperties.class)
public class AdminTimeSeriesConfig {
    /**
     * 不把 {@link TimeSeriesClient} 直接注册为 Spring bean，避免 Admin 业务误注入
     * {@link com.unisence.iot.timeseries.TimeSeriesWriter}。
     * Reader / Provisioner 必须各包一层只实现目标接口的视图：若把 {@code holder.client}
     * 原样返回，两个 bean 的运行时类型都是 {@link TimeSeriesClient}，构造注入会因
     * 「需要 1 个、找到 2 个」启动失败。
     */
    static final class ClientHolder implements AutoCloseable {
        private final TimeSeriesClient client;

        ClientHolder(TimeSeriesClient client) {
            this.client = client;
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private record ReaderView(TimeSeriesClient client) implements TimeSeriesReader {
        @Override
        public List<PropertyValue> latestProperties(String productKey, String deviceCode) {
            return client.latestProperties(productKey, deviceCode);
        }

        @Override
        public List<PropertyHistoryPoint> propertyHistory(String productKey, String deviceCode, String identifier,
                                                          String dataType, int retentionDays,
                                                          long from, long to, long bucketMillis) {
            return client.propertyHistory(productKey, deviceCode, identifier, dataType, retentionDays,
                                          from, to, bucketMillis);
        }

        @Override
        public TimeSeriesPage<PropertyValue> rawProperties(String productKey, String deviceCode, String identifier,
                                                           String dataType, int retentionDays,
                                                           long from, long to, long offset, int limit) {
            return client.rawProperties(productKey, deviceCode, identifier, dataType, retentionDays,
                                        from, to, offset, limit);
        }

        @Override
        public TimeSeriesPage<DeviceEventRow> events(String productKey, String deviceCode, String identifier,
                                                     long from, long to, long offset, int limit) {
            return client.events(productKey, deviceCode, identifier, from, to, offset, limit);
        }

        @Override
        public long countEvents(String productKey, String identifier, long from, long to) {
            return client.countEvents(productKey, identifier, from, to);
        }

        @Override
        public TimeSeriesPage<DeviceOnlineLog> onlineLogs(String productKey, String deviceCode,
                                                          long from, long to, long offset, int limit) {
            return client.onlineLogs(productKey, deviceCode, from, to, offset, limit);
        }

        @Override
        public List<DeviceOnlineLog> onlineHistory(String productKey,
                                                   String deviceCode,
                                                   long from,
                                                   long to,
                                                   int limit) {
            return client.onlineHistory(productKey, deviceCode, from, to, limit);
        }

        @Override
        public Integer previousOnlineEvent(String productKey, String deviceCode, long before) {
            return client.previousOnlineEvent(productKey, deviceCode, before);
        }

        @Override
        public void close() {
            // 生命周期只由 ClientHolder 关闭，避免 Reader bean 被推断为 AutoCloseable 时提前关掉共享客户端
        }
    }

    private record ProvisionerView(TimeSeriesClient client) implements TimeSeriesProvisioner {
        @Override
        public void ensureEventTable(EventTableSpec spec, boolean applyTtlChange) {
            client.ensureEventTable(spec, applyTtlChange);
        }
    }

    @Bean(destroyMethod = "close")
    ClientHolder adminTimeSeriesClient(AdminTimeSeriesProperties props) {
        Map<String, String> options = new LinkedHashMap<>();
        if (props.getJdbcUrl() != null && !props.getJdbcUrl().isBlank()) options.put("jdbc-url", props.getJdbcUrl());
        options.put("write-timeout-ms", Integer.toString(props.getWriteTimeoutMs()));
        TimeSeriesConfig config = new TimeSeriesConfig(
            props.getType(), props.getEndpoints(), props.getDatabase(), props.getUser(), props.getPassword(),
            props.getPoolMaxSize(), props.getConnectionTimeoutMs(), options);
        TimeSeriesClient client = TimeSeriesClients.open(config);
        log.info("Admin 时序数据库客户端已建立: type={} endpoints={} db={} poolMaxSize={}",
                 config.type(), config.endpoints(), config.database(), config.poolMaxSize());
        return new ClientHolder(client);
    }

    @Bean(destroyMethod = "")
    public TimeSeriesReader adminTimeSeriesReader(ClientHolder holder) {
        return new ReaderView(holder.client);
    }

    @Bean
    public TimeSeriesProvisioner adminTimeSeriesProvisioner(ClientHolder holder) {
        return new ProvisionerView(holder.client);
    }
}

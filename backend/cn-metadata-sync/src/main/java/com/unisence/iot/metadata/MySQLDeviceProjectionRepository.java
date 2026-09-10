package com.unisence.iot.metadata;

import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * {@link DeviceProjectionRepository} 的 MySQL 实现（metadata-sync-bus.md §6.7）。
 *
 * <p>查询形状固定为 {@code product_id = ? AND device_code IN (...)}：
 * 走 {@code uk_dev_product_code_deleted} 唯一索引，且把一个 poll 批次里同产品的设备合成一条 SQL。
 */
@Slf4j
public final class MySQLDeviceProjectionRepository implements DeviceProjectionRepository {

    private static final String SELECT_BY_PRODUCT_AND_CODES = """
        SELECT device_id, product_id, device_code, device_name, gateway_id,
               node_type, longitude, latitude, device_form_data, version
          FROM us_iot_device
         WHERE deleted = 0 AND product_id = ? AND device_code IN (%s)
        """;

    /**
     * 网关编码批量补齐：子设备的 gateway_id 指向另一台设备，需要它的 device_code。
     */
    private static final String SELECT_GATEWAY_CODES = """
        SELECT device_id, device_code FROM us_iot_device WHERE deleted = 0 AND device_id IN (%s)
        """;

    private final Pool pool;
    private final MetadataProperties properties;

    public MySQLDeviceProjectionRepository(Pool pool, MetadataProperties properties) {
        this.pool = pool;
        this.properties = properties;
    }

    @Override
    public Map<DeviceRef, DeviceCacheEnvelope> loadBatch(Set<DeviceRef> refs, EngineMetadataSnapshot expectedRoot) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        // 按产品分组：同产品的设备合成一条 SQL，避免 N+1
        Map<String, List<String>> codesByProductKey = new HashMap<>();
        for (DeviceRef ref : refs) {
            codesByProductKey.computeIfAbsent(ref.productKey(), k -> new ArrayList<>()).add(ref.deviceCode());
        }

        Map<DeviceRef, RawDeviceRow> rows = new LinkedHashMap<>();
        Set<Long> gatewayIds = new HashSet<>();
        for (var entry : codesByProductKey.entrySet()) {
            ProductRuntimeMeta product = expectedRoot.product(entry.getKey());
            if (product == null) {
                // 产品在本根里不存在：该批设备无从校验物模型，交给未知设备流程处理
                continue;
            }
            List<String> codes = entry.getValue();
            for (int from = 0; from < codes.size(); from += properties.deviceDbFallbackBatchSize()) {
                List<String> batch = codes.subList(from,
                                                   Math.min(codes.size(),
                                                            from + properties.deviceDbFallbackBatchSize()));
                RowSet<Row> result = pool
                    .preparedQuery(SqlIn.expand(SELECT_BY_PRODUCT_AND_CODES, batch.size()))
                    .execute(SqlIn.tuple(product.productId(), batch))
                    .await();
                for (Row row : result) {
                    RawDeviceRow raw = toRaw(row, product);
                    rows.put(raw.ref(), raw);
                    if (raw.gatewayId() != null) {
                        gatewayIds.add(raw.gatewayId());
                    }
                }
            }
        }
        Map<Long, String> gatewayCodes = loadGatewayCodes(gatewayIds);

        Map<DeviceRef, DeviceCacheEnvelope> projections = new LinkedHashMap<>(rows.size());
        for (RawDeviceRow raw : rows.values()) {
            ProductRuntimeMeta product = expectedRoot.product(raw.ref().productKey());
            if (product == null) {
                continue;
            }
            try {
                DeviceRuntimeMeta value = new DeviceRuntimeMeta(
                    raw.deviceId(),
                    product.productId(),
                    raw.ref(),
                    raw.deviceName(),
                    raw.gatewayId(),
                    raw.gatewayId() == null ? null : gatewayCodes.get(raw.gatewayId()),
                    raw.nodeType(),
                    raw.longitude(),
                    raw.latitude(),
                    DeviceFormProjector.project(product, raw.formData()));
                projections.put(raw.ref(),
                                DeviceCacheEnvelope.of(raw.rowVersion(), product.deviceFormVersion(), value));
            } catch (RuntimeException e) {
                // 单台设备的表单脏数据只跳过它自己，不能让整批回源失败
                log.error("构建设备投影失败，已跳过该设备: ref={}", raw.ref().deviceKey(), e);
            }
        }
        return projections;
    }

    private Map<Long, String> loadGatewayCodes(Set<Long> gatewayIds) {
        if (gatewayIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> codes = new HashMap<>(gatewayIds.size());
        List<Long> ids = new ArrayList<>(gatewayIds);
        for (int from = 0; from < ids.size(); from += properties.deviceDbFallbackBatchSize()) {
            List<Long> batch = ids.subList(from,
                                           Math.min(ids.size(), from + properties.deviceDbFallbackBatchSize()));
            RowSet<Row> result = pool
                .preparedQuery(SqlIn.expand(SELECT_GATEWAY_CODES, batch.size()))
                .execute(SqlIn.tuple(batch))
                .await();
            for (Row row : result) {
                codes.put(row.getLong("device_id"), row.getString("device_code"));
            }
        }
        return codes;
    }

    private static RawDeviceRow toRaw(Row row, ProductRuntimeMeta product) {
        String formJson = SqlJson.text(row, "device_form_data");
        Map<String, Object> formData = formJson == null || formJson.isBlank()
            ? Map.of()
            : new JsonObject(formJson).getMap();
        java.math.BigDecimal longitude = row.getBigDecimal("longitude");
        java.math.BigDecimal latitude = row.getBigDecimal("latitude");
        return new RawDeviceRow(
            row.getLong("device_id"),
            new DeviceRef(product.productKey(), row.getString("device_code")),
            row.getString("device_name"),
            row.getLong("gateway_id"),
            row.getInteger("node_type"),
            longitude == null ? null : longitude.doubleValue(),
            latitude == null ? null : latitude.doubleValue(),
            formData,
            row.getInteger("version"));
    }

    private record RawDeviceRow(
        long deviceId,
        DeviceRef ref,
        String deviceName,
        Long gatewayId,
        int nodeType,
        Double longitude,
        Double latitude,
        Map<String, Object> formData,
        int rowVersion) {
    }
}

package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备域加载器（metadata-sync-bus.md §13.3、§八）。
 *
 * <p>这是整个设计里最需要克制的一个 loader：设备是百万级的，任何「顺手多查一列」
 * 都会被乘以 100 万。因此增量与全量都<b>只读四列</b>
 * {@code device_id / version / product_id / device_code}，绝不构造 {@link DeviceRuntimeMeta}。
 */
@Slf4j
public final class DeviceMetadataLoader implements MetadataScopeLoader {

    private static final String SELECT_BY_IDS = """
        SELECT device_id, version, product_id, device_code, deleted
          FROM us_iot_device
         WHERE device_id IN (%s)
        """;

    /**
     * 全量扫描固定用主键游标分页。
     *
     * <p><b>禁止 {@code OFFSET} 深分页</b>：MySQL 的 {@code LIMIT n OFFSET m} 需要先扫描并丢弃
     * 前 m 行，扫到百万行时单页就要读几十万行，总代价是 O(n²)。主键游标每页都是一次
     * 范围扫描，恒定代价。
     *
     * <p>同样禁止 {@code selectList()} 一次装入：百万行一次性物化会直接把堆打满。
     */
    private static final String SELECT_PAGE = """
        SELECT device_id, version, product_id, device_code
          FROM us_iot_device
         WHERE deleted = 0 AND device_id > ?
         ORDER BY device_id
         LIMIT ?
        """;

    private final MetadataProperties properties;

    public DeviceMetadataLoader(MetadataProperties properties) {
        this.properties = properties;
    }

    @Override
    public MetaKeyEnum metaKey() {
        return MetaKeyEnum.IOT_DEVICE;
    }

    @Override
    public MetadataRawPatch.DeviceGenerationRawPatch load(Set<Long> scopeIds, MetadataReadView readView) {
        if (scopeIds.contains(0L)) {
            return fullRebuild(readView);
        }
        return incremental(scopeIds, readView);
    }

    /**
     * 增量：只取受影响设备的版本与身份，不碰表单、不碰状态。
     */
    private MetadataRawPatch.DeviceGenerationRawPatch incremental(Set<Long> scopeIds, MetadataReadView readView) {
        Map<Long, Integer> upserts = new HashMap<>();
        Set<Long> removals = new HashSet<>();
        List<DeviceRef> additions = new ArrayList<>();
        Set<Long> found = new HashSet<>();
        Map<Long, ProductRuntimeMeta> products = readView.candidateProducts();

        // 定长分批：SQL 文本随 IN 列表长度变化，不分批会让预编译缓存被各种批大小持续冲刷
        List<Long> ids = new ArrayList<>(scopeIds);
        int batchSize = properties.deviceDbFallbackBatchSize();
        for (int from = 0; from < ids.size(); from += batchSize) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + batchSize));
            RowSet<Row> rows = readView.connection()
                .preparedQuery(SqlIn.expand(SELECT_BY_IDS, batch.size()))
                .execute(SqlIn.tuple(batch))
                .await();
            for (Row row : rows) {
                long deviceId = row.getLong("device_id");
                found.add(deviceId);
                long deleted = row.getLong("deleted");
                if (deleted != 0) {
                    removals.add(deviceId);
                    continue;
                }
                upserts.put(deviceId, row.getInteger("version"));
                long productId = row.getLong("product_id");
                ProductRuntimeMeta product = products.get(productId);
                if (product == null) {
                    // 设备指向已删除或非普通产品：目录里留着它只会让消息在物模型校验时失败，
                    // 不如当作不存在，由未知设备修复流程给出确定结论
                    log.warn("设备所属产品不在候选快照中，已跳过: deviceId={} productId={}", deviceId, productId);
                    removals.add(deviceId);
                    upserts.remove(deviceId);
                    continue;
                }
                additions.add(new DeviceRef(product.productKey(), row.getString("device_code")));
            }
        }
        // 请求了却查不到 = 行已被物理清理；同样按删除处理
        for (Long scopeId : scopeIds) {
            if (!found.contains(scopeId)) {
                removals.add(scopeId);
            }
        }
        return new MetadataRawPatch.DeviceGenerationRawPatch(scopeIds, upserts, removals, additions, null);
    }

    /**
     * 全域重建：主键游标流式扫描，直接构建 primitive shard + Bloom。
     *
     * <p>整个过程中<b>没有一个 {@link DeviceRuntimeMeta}</b> 被创建 —— 这正是
     * 「100 万设备启动只构建紧凑目录」这条验收项的实现。
     */
    private MetadataRawPatch.DeviceGenerationRawPatch fullRebuild(MetadataReadView readView) {
        Map<Long, ProductRuntimeMeta> products = readView.candidateProducts();
        ShardedDeviceCatalog.Builder builder = new ShardedDeviceCatalog.Builder(
            properties.deviceCatalogShardCount(),
            properties.deviceCatalogMaxEntries(),
            properties.deviceExistenceFpp());

        long cursor = 0;
        long started = System.nanoTime();
        int scanned = 0;
        int skipped = 0;
        while (true) {
            RowSet<Row> rows = readView.connection()
                .preparedQuery(SELECT_PAGE)
                .execute(Tuple.of(cursor, properties.deviceCatalogLoadPageSize()))
                .await();
            if (rows.size() == 0) {
                break;
            }
            for (Row row : rows) {
                long deviceId = row.getLong("device_id");
                cursor = deviceId;
                scanned++;
                ProductRuntimeMeta product = products.get(row.getLong("product_id"));
                if (product == null) {
                    skipped++;
                    continue;
                }
                builder.add(deviceId, row.getInteger("version"),
                            new DeviceRef(product.productKey(), row.getString("device_code")));
            }
            if (builder.size() > properties.deviceCatalogMaxEntries()) {
                // 超上限即失败并保留 LKG：宁可不更新，也不能让目录退化成百万完整对象
                throw new IllegalStateException("设备版本目录超过保护上限: " + builder.size()
                                                    + " > metadata.device-catalog-max-entries="
                                                    + properties.deviceCatalogMaxEntries());
            }
            if (rows.size() < properties.deviceCatalogLoadPageSize()) {
                break;
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        ShardedDeviceCatalog catalog = builder.build();
        log.info("设备版本目录全量重建完成: 扫描行数={} 入目录={} 跳过(产品缺失)={} 估算字节={} 耗时={}ms",
                 scanned, catalog.size(), skipped, catalog.estimatedBytes(), elapsedMs);
        return new MetadataRawPatch.DeviceGenerationRawPatch(
            Set.of(0L), Map.of(), Set.of(), List.of(), catalog);
    }
}

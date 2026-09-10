package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.rule.sdk.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 物模型域加载器（metadata-sync-bus.md §13.3）。
 *
 * <p>一次查齐受影响产品的属性与事件，整体替换该产品的物模型条目 —— 不做「只更新变化的那条属性」
 * 的细粒度合并：物模型条目本身就很小，整条替换既简单又不可能拼出半新半旧的定义。
 */
@Slf4j
public final class ThingModelMetadataLoader implements MetadataScopeLoader {

    private static final String SELECT_PROPERTIES_ALL = """
        SELECT product_id, identifier, property_name, data_type, access_mode, unit, retention_days
          FROM us_iot_tm_property
         WHERE deleted = 0
        """;
    private static final String SELECT_PROPERTIES_BY_IDS = SELECT_PROPERTIES_ALL + " AND product_id IN (%s)";

    private static final String SELECT_EVENTS_ALL = """
        SELECT product_id, identifier, event_name, event_type, input_params, ttl_enabled, ttl_value, ttl_unit
          FROM us_iot_tm_event
         WHERE deleted = 0
        """;
    private static final String SELECT_EVENTS_BY_IDS = SELECT_EVENTS_ALL + " AND product_id IN (%s)";

    private final MetadataProperties properties;

    public ThingModelMetadataLoader(MetadataProperties properties) {
        this.properties = properties;
    }

    @Override
    public MetaKeyEnum metaKey() {
        return MetaKeyEnum.IOT_THING_MODEL;
    }

    @Override
    public MetadataRawPatch.ThingModelRawPatch load(Set<Long> productIds, MetadataReadView readView) {
        boolean fullDomain = productIds.contains(0L);
        Map<Long, Map<String, PropertyDefinition>> propertiesByProduct =
            loadProperties(productIds, readView, fullDomain);
        Map<Long, Map<String, EventDefinition>> eventsByProduct = loadEvents(productIds, readView, fullDomain);

        int definitionCount = propertiesByProduct.values().stream().mapToInt(Map::size).sum()
            + eventsByProduct.values().stream().mapToInt(Map::size).sum();
        if (fullDomain && definitionCount > properties.thingModelDefinitionMaxEntries()) {
            throw new IllegalStateException("物模型定义数超过保护上限: " + definitionCount
                                                + " > metadata.thing-model-definition-max-entries="
                                                + properties.thingModelDefinitionMaxEntries());
        }

        Set<Long> all = new HashSet<>(propertiesByProduct.keySet());
        all.addAll(eventsByProduct.keySet());
        Map<Long, ThingModelSnapshot> snapshots = new HashMap<>(all.size());
        for (Long productId : all) {
            snapshots.put(productId, new ThingModelSnapshot(
                propertiesByProduct.getOrDefault(productId, Map.of()),
                eventsByProduct.getOrDefault(productId, Map.of())));
        }

        // 请求了却一条定义都没读到 = 物模型已清空或产品已删除，必须从候选根移除该条目
        Set<Long> missing = new HashSet<>();
        if (!fullDomain) {
            for (Long productId : productIds) {
                if (!snapshots.containsKey(productId)) {
                    missing.add(productId);
                }
            }
        }
        return new MetadataRawPatch.ThingModelRawPatch(productIds, snapshots, missing);
    }

    private Map<Long, Map<String, PropertyDefinition>> loadProperties(
        Set<Long> productIds, MetadataReadView readView, boolean fullDomain) {
        Map<Long, Map<String, PropertyDefinition>> byProduct = new HashMap<>();
        for (Row row : query(SELECT_PROPERTIES_ALL, SELECT_PROPERTIES_BY_IDS, productIds, readView, fullDomain)) {
            long productId = row.getLong("product_id");
            String identifier = row.getString("identifier");
            String dataTypeCode = row.getString("data_type");
            PropertyDataType dataType;
            try {
                dataType = PropertyDataType.fromCode(dataTypeCode);
            } catch (IllegalArgumentException e) {
                // 单条脏定义只跳过它自己：让一条越界的 data_type 拖垮整个候选根，
                // 会把「一个产品配错」放大成「全平台元数据停止收敛」
                log.error("物模型 data_type 取值域外，已跳过该属性: productId={} identifier={} dataType={}",
                          productId, identifier, dataTypeCode, e);
                continue;
            }
            byProduct.computeIfAbsent(productId, k -> new HashMap<>())
                .put(identifier, new PropertyDefinition(
                    identifier,
                    row.getString("property_name"),
                    dataType,
                    row.getInteger("access_mode"),
                    row.getString("unit"),
                    row.getInteger("retention_days")));
        }
        return byProduct;
    }

    private Map<Long, Map<String, EventDefinition>> loadEvents(
        Set<Long> productIds, MetadataReadView readView, boolean fullDomain) {
        Map<Long, Map<String, EventDefinition>> byProduct = new HashMap<>();
        for (Row row : query(SELECT_EVENTS_ALL, SELECT_EVENTS_BY_IDS, productIds, readView, fullDomain)) {
            long productId = row.getLong("product_id");
            String identifier = row.getString("identifier");
            try {
                byProduct.computeIfAbsent(productId, k -> new HashMap<>())
                    .put(identifier.toLowerCase(java.util.Locale.ROOT), new EventDefinition(
                        identifier,
                        row.getString("event_name"),
                        EventLevel.fromCode(row.getInteger("event_type")),
                        parseParams(SqlJson.text(row, "input_params")),
                        Boolean.TRUE.equals(row.getBoolean("ttl_enabled")),
                        row.getInteger("ttl_value"),
                        row.getString("ttl_unit")));
            } catch (IllegalArgumentException e) {
                log.error("事件定义取值域外，已跳过: productId={} identifier={}", productId, identifier, e);
            }
        }
        return byProduct;
    }

    private RowSet<Row> query(String selectAll, String selectByIds, Set<Long> productIds,
                              MetadataReadView readView, boolean fullDomain) {
        if (fullDomain) {
            return readView.connection().preparedQuery(selectAll).execute().await();
        }
        return readView.connection()
            .preparedQuery(SqlIn.expand(selectByIds, productIds.size()))
            .execute(SqlIn.tuple(productIds))
            .await();
    }

    private static List<EventParamDefinition> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonArray array = new JsonArray(json);
        List<EventParamDefinition> params = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonObject item = array.getJsonObject(i);
            params.add(new EventParamDefinition(
                item.getString("identifier"),
                item.getString("name"),
                PropertyDataType.fromCode(item.getString("dataType"))));
        }
        return params;
    }
}

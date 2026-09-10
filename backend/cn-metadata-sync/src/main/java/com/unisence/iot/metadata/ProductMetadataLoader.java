package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 产品域加载器（metadata-sync-bus.md §13.3）。
 *
 * <p>只读 engine 真正会用的四列。产品描述、图标、厂商这些只在管理端展示的列不查 ——
 * 查回来除了增加网络与候选构建的复制量之外没有任何用处。
 */
@Slf4j
public final class ProductMetadataLoader implements MetadataScopeLoader {

    /**
     * 只加载普通产品：标准产品是模板，不挂设备、不参与运行链路。
     */
    private static final String SELECT_ALL = """
        SELECT product_id, product_key, online_ttl_seconds, device_form_version, device_form_schema
          FROM us_iot_product
         WHERE deleted = 0 AND product_type = 1
        """;

    private static final String SELECT_BY_IDS = SELECT_ALL + " AND product_id IN (%s)";

    private final int defaultTtlSeconds;
    private final MetadataProperties properties;

    public ProductMetadataLoader(int defaultTtlSeconds, MetadataProperties properties) {
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.properties = properties;
    }

    @Override
    public MetaKeyEnum metaKey() {
        return MetaKeyEnum.IOT_PRODUCT;
    }

    @Override
    public MetadataRawPatch.ProductRawPatch load(Set<Long> scopeIds, MetadataReadView readView) {
        boolean fullDomain = scopeIds.contains(0L);
        RowSet<Row> rows = fullDomain
            ? readView.connection().preparedQuery(SELECT_ALL).execute().await()
            : readView.connection().preparedQuery(SqlIn.expand(SELECT_BY_IDS, scopeIds.size()))
            .execute(SqlIn.tuple(scopeIds)).await();

        List<ProductRuntimeMeta> products = new ArrayList<>(rows.size());
        Set<Long> found = new HashSet<>();
        for (Row row : rows) {
            long productId = row.getLong("product_id");
            Integer ttl = row.getInteger("online_ttl_seconds");
            Integer formVersion = row.getInteger("device_form_version");
            products.add(new ProductRuntimeMeta(
                productId,
                row.getString("product_key"),
                ttl == null || ttl <= 0 ? defaultTtlSeconds : ttl,
                formVersion == null ? 0 : formVersion,
                ruleVisibleFormKeys(productId, SqlJson.text(row, "device_form_schema"))));
            found.add(productId);
        }
        if (fullDomain && products.size() > properties.productMaxEntries()) {
            // 超过保护上限即放弃候选根、保留 LKG：无界加载会在启动期直接把堆打满
            throw new IllegalStateException("产品数超过保护上限: " + products.size()
                                                + " > metadata.product-max-entries=" + properties.productMaxEntries());
        }

        // 请求了却没查到 = 已删除；候选根必须显式移除，否则删掉的产品会永远留在内存里
        Set<Long> missing = new HashSet<>();
        if (!fullDomain) {
            for (Long scopeId : scopeIds) {
                if (!found.contains(scopeId)) {
                    missing.add(scopeId);
                }
            }
        }
        return new MetadataRawPatch.ProductRawPatch(scopeIds, products, missing);
    }

    /**
     * 从 active schema 抽出 {@code sensitive != true} 的字段 key。
     *
     * <p>只留 key 名，不留整份 schema：设备投影唯一需要 schema 回答的问题就是
     * 「这个 key 能不能给规则看」。把 schema JSON 常驻内存既要乘以产品数，
     * 也会诱使后续代码在热路径上重新解析 JSON。
     *
     * <p>schema 结构为 {@code {groups:[{fields:[{key,sensitive,...}]}]}}（device-form-design.md）。
     */
    private static Set<String> ruleVisibleFormKeys(long productId, String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        try {
            JsonArray groups = new JsonObject(schemaJson).getJsonArray("groups");
            if (groups == null) {
                return Set.of();
            }
            for (int g = 0; g < groups.size(); g++) {
                JsonObject group = groups.getJsonObject(g);
                JsonArray fields = group == null ? null : group.getJsonArray("fields");
                if (fields == null) {
                    continue;
                }
                for (int f = 0; f < fields.size(); f++) {
                    JsonObject field = fields.getJsonObject(f);
                    if (field == null) {
                        continue;
                    }
                    String key = field.getString("key");
                    if (key == null || key.isBlank() || Boolean.TRUE.equals(field.getBoolean("sensitive"))) {
                        continue;
                    }
                    keys.add(key);
                }
            }
        } catch (RuntimeException e) {
            // schema 损坏时退化成「无可见字段」而不是抛出：宁可规则读不到表单，
            // 也不能因为一个产品的脏 schema 让整个候选根构建失败
            log.error("解析产品表单 schema 失败，该产品表单对规则不可见: productId={}", productId, e);
            return Set.of();
        }
        return keys;
    }
}

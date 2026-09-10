package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.crypto.AesGcmCipher;
import com.unisence.iot.common.device.DeviceFormProcessor;
import com.unisence.iot.message.DeviceCreateMessage;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * device_create 自动建档：产品/schema 解析、幂等设备行、表单索引与元数据提交。
 *
 * <p><b>批量提交，不逐条建档</b>（engine-hotpath-optimization.md §十）。逐条建档的成本主体
 * 不是 I/O 次数，而是每条各等一轮元数据收敛 —— 而收敛是全局 single-flight，
 * 并行化缩短不了它。批内聚合后<b>全部新建设备共享一个 {@code commitSeq}</b>，
 * 调用方只需等待一次收敛。
 *
 * <p><b>预校验在事务之外</b>：产品不存在、网关缺失、表单不合法都在进事务前判定并单独返回，
 * 一条脏数据不会让整批回滚。
 */
@Slf4j
public final class DeviceCreateRepository {

    private static final String SELECT_PRODUCTS = """
        SELECT product_key, product_id, product_type, device_form_version, device_form_schema
          FROM us_iot_product WHERE product_key IN (%s) AND deleted = 0
        """;
    private static final String SELECT_GATEWAYS = """
        SELECT device_id, device_code FROM us_iot_device
         WHERE product_id = ? AND device_code IN (%s) AND node_type = 2 AND deleted = 0
        """;
    private static final String SELECT_EXISTING = """
        SELECT device_id, device_code FROM us_iot_device
         WHERE product_id = ? AND device_code IN (%s) AND deleted = 0
        """;
    private static final String INSERT_DEVICE = """
        INSERT INTO us_iot_device
          (product_id, device_code, device_name, gateway_id, node_type, status,
           device_form_data, create_time, update_time, deleted, version)
        VALUES (?, ?, ?, ?, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)
        ON DUPLICATE KEY UPDATE device_id = LAST_INSERT_ID(device_id)
        """;
    private static final String INSERT_INDEX = """
        INSERT INTO us_iot_device_form_index
          (device_id, product_id, schema_version, field_key,
           value_text, value_decimal, value_boolean, create_time)
        VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
    private static final String ADVANCE_HEAD = """
        UPDATE us_sys_metadata_head
           SET committed_seq = LAST_INSERT_ID(committed_seq + 1),
               update_time = CURRENT_TIMESTAMP, version = version + 1
         WHERE head_code = 'MAIN' AND deleted = 0
        """;
    private static final String SELECT_LAST_SEQ = "SELECT LAST_INSERT_ID() AS seq";
    private static final String INSERT_CHANGE = """
        INSERT INTO us_sys_metadata_change (commit_seq, meta_key, scope_id, create_time)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP(3))
        """;

    private final Pool pool;
    private final AesGcmCipher cipher;

    public DeviceCreateRepository(Pool pool, AesGcmCipher cipher) {
        this.pool = pool;
        this.cipher = cipher;
    }

    /**
     * 批量建档。入参须已按 {@link DeviceRef} 去重。
     *
     * <p>两阶段：先在事务外批量预校验（产品、网关、表单），再把通过者放进<b>单个事务</b>提交。
     */
    public DeviceCreateBatchCommit createAllIfAbsent(List<DeviceCreateMessage> messages) {
        if (messages.isEmpty()) {
            return DeviceCreateBatchCommit.rejectedOnly(Map.of());
        }
        Map<DeviceRef, String> rejected = new LinkedHashMap<>();
        List<PreparedDevice> prepared = precheck(messages, rejected);
        if (prepared.isEmpty()) {
            return DeviceCreateBatchCommit.rejectedOnly(rejected);
        }
        return commit(prepared, rejected);
    }

    // ────────────────────────────── 预校验（事务外） ──────────────────────────────

    /**
     * 批量预校验。产品与网关各一次 {@code IN} 查询，表单校验是纯 CPU。
     *
     * <p>被拒者写进 {@code rejected} 并<b>不进入返回列表</b> —— 它们从不参与事务。
     */
    private List<PreparedDevice> precheck(List<DeviceCreateMessage> messages,
                                          Map<DeviceRef, String> rejected) {
        SqlConnection connection = pool.getConnection().await();
        try {
            Set<String> productKeys = new LinkedHashSet<>();
            messages.forEach(m -> productKeys.add(m.productKey()));
            Map<String, Product> products = loadProducts(connection, List.copyOf(productKeys));

            // 网关按 productId 分组一次查完，避免每台子设备各查一次
            Map<Long, Set<String>> gatewayCodesByProduct = new LinkedHashMap<>();
            for (DeviceCreateMessage message : messages) {
                Product product = products.get(message.productKey());
                if (product != null && message.nodeType().requiresGatewayCode()) {
                    gatewayCodesByProduct
                        .computeIfAbsent(product.id(), unused -> new LinkedHashSet<>())
                        .add(message.gatewayCode());
                }
            }
            Map<Long, Map<String, Long>> gateways = new HashMap<>();
            gatewayCodesByProduct.forEach((productId, codes) ->
                                              gateways.put(productId,
                                                           loadGateways(connection, productId, List.copyOf(codes))));

            List<PreparedDevice> prepared = new ArrayList<>(messages.size());
            for (DeviceCreateMessage message : messages) {
                DeviceRef ref = new DeviceRef(message.productKey(), message.deviceCode());
                try {
                    prepared.add(prepareOne(message, ref, products, gateways));
                } catch (DeviceCreateRejected | DeviceFormProcessor.DeviceFormException e) {
                    log.warn("设备自动建档预校验被拒: productKey={} deviceCode={} reason={}",
                             message.productKey(), message.deviceCode(), e.getMessage());
                    rejected.put(ref, e.getMessage());
                }
            }
            return prepared;
        } finally {
            connection.close().await();
        }
    }

    private PreparedDevice prepareOne(DeviceCreateMessage message, DeviceRef ref,
                                      Map<String, Product> products,
                                      Map<Long, Map<String, Long>> gateways) {
        Product product = products.get(message.productKey());
        if (product == null) {
            throw new DeviceCreateRejected("PRODUCT_NOT_FOUND");
        }
        if (!product.deviceBearing()) {
            throw new DeviceCreateRejected("PRODUCT_NOT_DEVICE_BEARING");
        }
        Long gatewayId = null;
        if (message.nodeType().requiresGatewayCode()) {
            gatewayId = gateways.getOrDefault(product.id(), Map.of()).get(message.gatewayCode());
            if (gatewayId == null) {
                throw new DeviceCreateRejected("GATEWAY_NOT_FOUND");
            }
        }
        DeviceFormProcessor.PreparedForm form = DeviceFormProcessor.prepareCreate(
            product.schema(), message.formData(), cipher::encrypt);
        // form.stored() 已是 JSON 兼容的 Map。mapFrom(Object) 走 Jackson POJO 映射，
        // 而 engine 为保持轻量刻意不依赖 jackson-databind；直接用 Map 构造器交给 Vert.x 原生 codec
        String formJson = new JsonObject(form.stored()).encode();
        return new PreparedDevice(ref, message, product, gatewayId, formJson, form);
    }

    private static Map<String, Product> loadProducts(SqlConnection connection, List<String> productKeys) {
        Tuple args = Tuple.tuple();
        productKeys.forEach(args::addString);
        RowSet<Row> rows = connection.preparedQuery(placeholders(SELECT_PRODUCTS, productKeys.size()))
            .execute(args).await();
        Map<String, Product> products = new HashMap<>();
        for (Row row : rows) {
            String schemaJson = SqlJson.text(row, "device_form_schema");
            Map<String, Object> schema = schemaJson == null || schemaJson.isBlank()
                ? Map.of() : new JsonObject(schemaJson).getMap();
            Integer version = row.getInteger("device_form_version");
            // 非承载设备型也放进 map 并带标记：不这样做就只能回报 PRODUCT_NOT_FOUND，
            // 把「产品不存在」和「产品类型不对」混为一谈，排查时无从区分
            products.put(row.getString("product_key"),
                         new Product(row.getLong("product_id"), version == null ? 0 : version, schema,
                                     row.getInteger("product_type") == 1));
        }
        return products;
    }

    private static Map<String, Long> loadGateways(SqlConnection connection, long productId, List<String> codes) {
        Tuple args = Tuple.tuple().addLong(productId);
        codes.forEach(args::addString);
        RowSet<Row> rows = connection.preparedQuery(placeholders(SELECT_GATEWAYS, codes.size()))
            .execute(args).await();
        Map<String, Long> gateways = new HashMap<>();
        for (Row row : rows) {
            gateways.put(row.getString("device_code"), row.getLong("device_id"));
        }
        return gateways;
    }

    // ────────────────────────────── 提交（单事务） ──────────────────────────────

    /**
     * 单事务提交：先查已存在 → 批插 → 回查 → 一次分配水位 → 批写索引与变更目录。
     *
     * <p><b>为什么两次 SELECT</b>：{@code executeBatch} 取不到逐行 {@code LAST_INSERT_ID}，
     * 因此 deviceId 只能回查。前一次得到「本次之前就已存在」的集合，后一次得到「全部」，
     * 两者之差即本次真正新建者 —— 少了前一次就无法区分，
     * 重复写变更目录会让水位无谓膨胀（§10.4）。
     */
    private DeviceCreateBatchCommit commit(List<PreparedDevice> prepared, Map<DeviceRef, String> rejected) {
        SqlConnection connection = pool.getConnection().await();
        try {
            Transaction transaction = connection.begin().await();
            boolean committed = false;
            try {
                Map<Long, List<PreparedDevice>> byProduct = new LinkedHashMap<>();
                for (PreparedDevice device : prepared) {
                    byProduct.computeIfAbsent(device.product().id(), unused -> new ArrayList<>()).add(device);
                }

                Map<DeviceRef, Long> preExisting = new LinkedHashMap<>();
                byProduct.forEach((productId, devices) ->
                                      preExisting.putAll(selectExisting(connection, productId, devices)));

                List<Tuple> deviceRows = new ArrayList<>();
                for (PreparedDevice device : prepared) {
                    if (preExisting.containsKey(device.ref())) {
                        continue;
                    }
                    deviceRows.add(Tuple.tuple()
                                       .addLong(device.product().id())
                                       .addString(device.message().deviceCode())
                                       .addString(device.message().deviceName())
                                       .addLong(device.gatewayId())
                                       .addInteger(device.message().nodeType().code())
                                       .addString(device.formJson()));
                }
                if (!deviceRows.isEmpty()) {
                    // ON DUPLICATE KEY 不再用于取 id，但仍负责吸收并发实例的同时创建（§6.8）
                    connection.preparedQuery(INSERT_DEVICE).executeBatch(deviceRows).await();
                }

                Map<DeviceRef, Long> allIds = new LinkedHashMap<>();
                byProduct.forEach((productId, devices) ->
                                      allIds.putAll(selectExisting(connection, productId, devices)));

                Set<DeviceRef> inserted = new LinkedHashSet<>();
                for (PreparedDevice device : prepared) {
                    if (!preExisting.containsKey(device.ref()) && allIds.containsKey(device.ref())) {
                        inserted.add(device.ref());
                    }
                }
                if (inserted.isEmpty()) {
                    transaction.commit().await();
                    committed = true;
                    return new DeviceCreateBatchCommit(allIds, Set.of(), null, rejected);
                }

                // 一次分配，全部新建设备共享该水位。hasGap 的不变量是「每次分配 head 必写
                // 至少一个 scope 且 seq 连续」，共用不破坏它（§10.5）
                long commitSeq = allocateCommitSeq(connection);

                List<Tuple> indexRows = new ArrayList<>();
                List<Tuple> changeRows = new ArrayList<>();
                for (PreparedDevice device : prepared) {
                    if (!inserted.contains(device.ref())) {
                        continue;
                    }
                    long deviceId = allIds.get(device.ref());
                    for (DeviceFormProcessor.IndexValue index : device.form().indexes()) {
                        indexRows.add(Tuple.tuple()
                                          .addLong(deviceId)
                                          .addLong(device.product().id())
                                          .addInteger(device.product().schemaVersion())
                                          .addString(index.fieldKey())
                                          .addString(index.valueText())
                                          .addBigDecimal(index.valueDecimal())
                                          .addBoolean(index.valueBoolean()));
                    }
                    changeRows.add(Tuple.of(commitSeq, MetaKeyEnum.IOT_DEVICE.getKey(), deviceId));
                }
                if (!indexRows.isEmpty()) {
                    connection.preparedQuery(INSERT_INDEX).executeBatch(indexRows).await();
                }
                connection.preparedQuery(INSERT_CHANGE).executeBatch(changeRows).await();

                transaction.commit().await();
                committed = true;
                log.info("上行设备批量建档成功: 新建={} 已存在={} 被拒={} commitSeq={}",
                         inserted.size(), allIds.size() - inserted.size(), rejected.size(), commitSeq);
                return new DeviceCreateBatchCommit(allIds, inserted, commitSeq, rejected);
            } finally {
                if (!committed) {
                    rollbackQuietly(transaction);
                }
            }
        } finally {
            connection.close().await();
        }
    }

    private static Map<DeviceRef, Long> selectExisting(SqlConnection connection, long productId,
                                                       List<PreparedDevice> devices) {
        List<String> codes = devices.stream().map(d -> d.message().deviceCode()).toList();
        Tuple args = Tuple.tuple().addLong(productId);
        codes.forEach(args::addString);
        RowSet<Row> rows = connection.preparedQuery(placeholders(SELECT_EXISTING, codes.size()))
            .execute(args).await();
        Map<String, String> productKeyByCode = new HashMap<>();
        devices.forEach(d -> productKeyByCode.put(d.message().deviceCode(), d.message().productKey()));
        Map<DeviceRef, Long> found = new LinkedHashMap<>();
        for (Row row : rows) {
            String code = row.getString("device_code");
            String productKey = productKeyByCode.get(code);
            if (productKey != null) {
                found.put(new DeviceRef(productKey, code), row.getLong("device_id"));
            }
        }
        return found;
    }

    private static long allocateCommitSeq(SqlConnection connection) {
        if (connection.preparedQuery(ADVANCE_HEAD).execute().await().rowCount() != 1) {
            throw new IllegalStateException("us_sys_metadata_head MAIN 行缺失");
        }
        for (Row row : connection.preparedQuery(SELECT_LAST_SEQ).execute().await()) {
            return row.getLong("seq");
        }
        throw new IllegalStateException("读取 LAST_INSERT_ID 失败");
    }

    /**
     * 按参数个数展开 {@code IN (?, ?, ...)}。个数来自本批设备数，非外部输入，无注入面。
     */
    private static String placeholders(String template, int count) {
        return template.formatted("?, ".repeat(count - 1) + "?");
    }

    private static void rollbackQuietly(Transaction transaction) {
        try {
            transaction.rollback().await();
        } catch (Exception error) {
            log.warn("回滚设备批量建档事务失败", error);
        }
    }

    private record Product(long id, int schemaVersion, Map<String, Object> schema, boolean deviceBearing) {
    }

    /**
     * 预校验通过的设备：已解析出产品、网关与表单，进事务后不再需要任何额外查询。
     */
    private record PreparedDevice(DeviceRef ref, DeviceCreateMessage message, Product product,
                                  Long gatewayId, String formJson,
                                  DeviceFormProcessor.PreparedForm form) {
    }

    public static final class DeviceCreateRejected extends IllegalArgumentException {
        public DeviceCreateRejected(String reason) {
            super(reason);
        }
    }
}

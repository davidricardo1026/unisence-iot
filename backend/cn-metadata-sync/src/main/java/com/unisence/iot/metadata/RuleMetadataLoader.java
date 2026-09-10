package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.config.*;
import com.unisence.iot.rule.sdk.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 规则域加载器（metadata-sync-bus.md §13.3）。
 *
 * <p><b>只读脚本原文，绝不在此编译</b>。Groovy 编译动辄数百毫秒，放在只读事务里会让
 * read view 长时间挂起，进而拖住 InnoDB 的 undo 清理。编译统一由
 * {@code MetadataSnapshotBuilder} 在事务提交之后进行。
 *
 * <h2>为什么增量加载要查三张表</h2>
 * 变更日志里的 {@code scope_id} 是一个裸数字，而即时规则、窗口规则与透传路由各有独立的
 * {@code AUTO_INCREMENT} —— 拿到「12 号规则变了」时无法判断是哪张表的 12 号。
 * 因此三张表都按该 id 查一次，最多多读一行。
 *
 * <p>这个代价是可接受的，因为规则变更是<b>低频</b>操作（改一次阈值不是热路径）；
 * 而替代方案（给规则域拆两个 MetaKey）要动水位合并、反熵、变更日志三处机制，
 * 换来的只是每次少读一行。
 */
@Slf4j
public final class RuleMetadataLoader implements MetadataScopeLoader {

    /**
     * 只加载已启用规则：停用规则不参与执行，加载它只是白白占用编译时间与内存。
     */
    private static final String SELECT_INSTANT = """
        SELECT rule_id, rule_code, message_type, listener_config, value_config, emit_mode,
               filter_script, script_sha256, error_policy, revision
          FROM us_iot_rule_instant
         WHERE deleted = 0 AND status = 1
        """;
    private static final String SELECT_INSTANT_BY_IDS = SELECT_INSTANT + " AND rule_id IN (%s)";

    private static final String SELECT_WINDOW = """
        SELECT rule_id, rule_code, message_type, listener_config, window_config, aggregate_config,
               filter_script, script_sha256, error_policy, revision
          FROM us_iot_rule_window
         WHERE deleted = 0 AND status = 1
        """;
    private static final String SELECT_WINDOW_BY_IDS = SELECT_WINDOW + " AND rule_id IN (%s)";

    /**
     * 档位按 severity 升序取出，与 {@code RuleDefinition.levels()} 的顺序一致 ——
     * 那个顺序就是运行期的判档顺序，在 SQL 里排好可以省掉一次内存排序，
     * 也让「库里看到的顺序」与「实际判档顺序」一眼对得上。
     */
    private static final String SELECT_LEVELS = """
        SELECT rule_kind, rule_id, level_id, level_code, severity, condition_kind,
               threshold_config, condition_script, output_script, cooldown_millis
          FROM us_iot_rule_level
         WHERE rule_kind = ?
         ORDER BY rule_id, severity
        """;
    private static final String SELECT_LEVELS_BY_IDS = """
        SELECT rule_kind, rule_id, level_id, level_code, severity, condition_kind,
               threshold_config, condition_script, output_script, cooldown_millis
          FROM us_iot_rule_level
         WHERE rule_kind = ? AND rule_id IN (%s)
         ORDER BY rule_id, severity
        """;

    private static final String SELECT_INSTANT_BINDINGS = """
        SELECT rule_id, product_id FROM us_iot_rule_instant_product
        """;
    private static final String SELECT_INSTANT_BINDINGS_BY_IDS =
        SELECT_INSTANT_BINDINGS + " WHERE rule_id IN (%s)";

    private static final String SELECT_WINDOW_BINDINGS = """
        SELECT rule_id, product_id FROM us_iot_rule_window_product
        """;
    private static final String SELECT_WINDOW_BINDINGS_BY_IDS =
        SELECT_WINDOW_BINDINGS + " WHERE rule_id IN (%s)";

    private static final String SELECT_LEVEL_TARGETS = """
        SELECT b.level_id, l.level_code, l.rule_kind, l.rule_id, o.output_id, o.target_topic, o.format
          FROM us_iot_rule_level_kafka_output b
          JOIN us_iot_rule_level l ON l.level_id = b.level_id
          JOIN us_iot_rule_kafka_output o ON o.output_id = b.output_id AND o.deleted = 0
         WHERE l.rule_kind = ?
         ORDER BY o.output_id
        """;
    private static final String SELECT_LEVEL_TARGETS_BY_IDS = """
        SELECT b.level_id, l.level_code, l.rule_kind, l.rule_id, o.output_id, o.target_topic, o.format
          FROM us_iot_rule_level_kafka_output b
          JOIN us_iot_rule_level l ON l.level_id = b.level_id
          JOIN us_iot_rule_kafka_output o ON o.output_id = b.output_id AND o.deleted = 0
         WHERE l.rule_kind = ? AND l.rule_id IN (%s)
         ORDER BY o.output_id
        """;

    private static final String SELECT_ROUTE = """
        SELECT rule_id, rule_code, message_type
          FROM us_iot_rule_route
         WHERE deleted = 0 AND status = 1
        """;
    private static final String SELECT_ROUTE_BY_IDS = SELECT_ROUTE + " AND rule_id IN (%s)";

    private static final String SELECT_ROUTE_PRODUCTS = """
        SELECT rule_id, product_id FROM us_iot_rule_route_product
        """;
    private static final String SELECT_ROUTE_PRODUCTS_BY_IDS =
        SELECT_ROUTE_PRODUCTS + " WHERE rule_id IN (%s)";

    private static final String SELECT_ROUTE_TARGETS = """
        SELECT b.rule_id, o.output_id, o.target_topic, o.format
          FROM us_iot_rule_route_kafka_output b
          JOIN us_iot_rule_kafka_output o ON o.output_id = b.output_id AND o.deleted = 0
         ORDER BY o.output_id
        """;
    private static final String SELECT_ROUTE_TARGETS_BY_IDS = """
        SELECT b.rule_id, o.output_id, o.target_topic, o.format
          FROM us_iot_rule_route_kafka_output b
          JOIN us_iot_rule_kafka_output o ON o.output_id = b.output_id AND o.deleted = 0
         WHERE b.rule_id IN (%s)
         ORDER BY o.output_id
        """;

    private final MetadataProperties properties;

    public RuleMetadataLoader(MetadataProperties properties) {
        this.properties = properties;
    }

    @Override
    public MetaKeyEnum metaKey() {
        return MetaKeyEnum.IOT_RULES;
    }

    @Override
    public MetadataRawPatch.RuleRawPatch load(Set<Long> ruleIds, MetadataReadView readView) {
        boolean fullDomain = ruleIds.contains(0L);

        RowSet<Row> instantRows = query(readView, fullDomain, ruleIds, SELECT_INSTANT, SELECT_INSTANT_BY_IDS);
        RowSet<Row> windowRows = query(readView, fullDomain, ruleIds, SELECT_WINDOW, SELECT_WINDOW_BY_IDS);
        RowSet<Row> routeRows = query(readView, fullDomain, ruleIds, SELECT_ROUTE, SELECT_ROUTE_BY_IDS);

        int total = instantRows.size() + windowRows.size();
        if (fullDomain && total > properties.ruleMaxEntries()) {
            throw new IllegalStateException("规则数超过保护上限: " + total
                                                + " > metadata.rule-max-entries=" + properties.ruleMaxEntries());
        }

        Set<Long> instantFound = idsOf(instantRows);
        Set<Long> windowFound = idsOf(windowRows);
        Set<Long> routeFound = idsOf(routeRows);

        Map<Long, List<LevelRow>> instantLevels =
            loadLevels(RuleKind.INSTANT, instantFound, readView, fullDomain);
        Map<Long, List<LevelRow>> windowLevels =
            loadLevels(RuleKind.WINDOW, windowFound, readView, fullDomain);
        Map<Long, Map<String, List<KafkaOutputTarget>>> instantLevelTargets =
            loadLevelTargets(RuleKind.INSTANT, instantFound, readView, fullDomain);
        Map<Long, Map<String, List<KafkaOutputTarget>>> windowLevelTargets =
            loadLevelTargets(RuleKind.WINDOW, windowFound, readView, fullDomain);
        Map<Long, Set<Long>> instantBindings = loadBindings(instantFound, readView, fullDomain,
                                                            SELECT_INSTANT_BINDINGS, SELECT_INSTANT_BINDINGS_BY_IDS);
        Map<Long, Set<Long>> windowBindings = loadBindings(windowFound, readView, fullDomain,
                                                           SELECT_WINDOW_BINDINGS, SELECT_WINDOW_BINDINGS_BY_IDS);
        Map<Long, Set<Long>> routeBindings = loadBindings(routeFound, readView, fullDomain,
                                                          SELECT_ROUTE_PRODUCTS, SELECT_ROUTE_PRODUCTS_BY_IDS);
        Map<Long, List<KafkaOutputTarget>> routeTargets =
            loadRouteTargets(routeFound, readView, fullDomain);

        List<MetadataRawPatch.RuleRow> rules = new ArrayList<>(total);
        for (Row row : instantRows) {
            long ruleId = row.getLong("rule_id");
            collect(rules, ruleId, RuleKind.INSTANT,
                    () -> toInstantRow(row, instantLevels.getOrDefault(ruleId, List.of()),
                                       instantBindings.getOrDefault(ruleId, Set.of()),
                                       instantLevelTargets.getOrDefault(ruleId, Map.of())));
        }
        for (Row row : windowRows) {
            long ruleId = row.getLong("rule_id");
            collect(rules, ruleId, RuleKind.WINDOW,
                    () -> toWindowRow(row, windowLevels.getOrDefault(ruleId, List.of()),
                                      windowBindings.getOrDefault(ruleId, Set.of()),
                                      windowLevelTargets.getOrDefault(ruleId, Map.of())));
        }

        List<MetadataRawPatch.RouteRuleRow> routes = new ArrayList<>(routeRows.size());
        for (Row row : routeRows) {
            long ruleId = row.getLong("rule_id");
            try {
                routes.add(new MetadataRawPatch.RouteRuleRow(
                    ruleId,
                    row.getString("rule_code"),
                    MessageType.fromCode(row.getString("message_type")),
                    routeBindings.getOrDefault(ruleId, Set.of()),
                    routeTargets.getOrDefault(ruleId, List.of())));
            } catch (RuntimeException e) {
                log.error("透传路由配置取值域外，已跳过该规则: ruleKey={}", RuleKind.ROUTE.ruleKey(ruleId), e);
            }
        }

        return new MetadataRawPatch.RuleRawPatch(ruleIds, rules,
                                                 missingKeys(fullDomain,
                                                             ruleIds,
                                                             instantFound,
                                                             windowFound,
                                                             routeFound),
                                                 routes);
    }

    /**
     * 请求了却没查到 = 已删除或已停用；两种情况在运行期是同一件事：不再执行它。
     *
     * <p><b>按「键」判定而不是按 id</b>：一个 scope_id 要在三张表里各查一次，
     * 只要有一张表没查到，对应那个键就该被移除。若按 id 判定，
     * 「即时 12 号已删、窗口 12 号仍在」会被当成「12 号还在」，
     * 那条已删除的即时规则将永远留在候选根里继续产生告警。
     */
    private static Set<String> missingKeys(boolean fullDomain, Set<Long> requested,
                                           Set<Long> instantFound, Set<Long> windowFound, Set<Long> routeFound) {
        if (fullDomain) {
            // 全域重建整体替换，不存在「残留」的概念
            return Set.of();
        }
        Set<String> missing = new HashSet<>();
        for (Long ruleId : requested) {
            if (!instantFound.contains(ruleId)) {
                missing.add(RuleKind.INSTANT.ruleKey(ruleId));
            }
            if (!windowFound.contains(ruleId)) {
                missing.add(RuleKind.WINDOW.ruleKey(ruleId));
            }
            if (!routeFound.contains(ruleId)) {
                missing.add(RuleKind.ROUTE.ruleKey(ruleId));
            }
        }
        return missing;
    }

    /**
     * 单条规则配置越界只跳过它：其余规则仍应生效。
     *
     * <p>admin 保存时本就该拦下这种数据，走到这里说明是历史脏数据或绕过接口的直改。
     */
    private static void collect(List<MetadataRawPatch.RuleRow> target, long ruleId, RuleKind kind,
                                java.util.function.Supplier<MetadataRawPatch.RuleRow> mapper) {
        try {
            target.add(mapper.get());
        } catch (RuntimeException e) {
            log.error("规则配置取值域外，已跳过该规则: ruleKey={}", kind.ruleKey(ruleId), e);
        }
    }

    private RowSet<Row> query(MetadataReadView readView, boolean fullDomain, Set<Long> ruleIds,
                              String selectAll, String selectByIds) {
        if (fullDomain) {
            return readView.connection().preparedQuery(selectAll).execute().await();
        }
        return readView.connection().preparedQuery(SqlIn.expand(selectByIds, ruleIds.size()))
            .execute(SqlIn.tuple(ruleIds)).await();
    }

    private static Set<Long> idsOf(RowSet<Row> rows) {
        Set<Long> ids = new HashSet<>(rows.size());
        for (Row row : rows) {
            ids.add(row.getLong("rule_id"));
        }
        return ids;
    }

    // ────────────────────────── 档位与绑定 ──────────────────────────

    private Map<Long, List<LevelRow>> loadLevels(RuleKind kind, Set<Long> ruleIds,
                                                 MetadataReadView readView, boolean fullDomain) {
        if (!fullDomain && ruleIds.isEmpty()) {
            return Map.of();
        }
        RowSet<Row> rows = fullDomain
            ? readView.connection().preparedQuery(SELECT_LEVELS)
            .execute(io.vertx.sqlclient.Tuple.of(kind.name())).await()
            : readView.connection().preparedQuery(SqlIn.expand(SELECT_LEVELS_BY_IDS, ruleIds.size()))
            .execute(prepend(kind.name(), ruleIds)).await();

        Map<Long, List<LevelRow>> levels = new HashMap<>();
        for (Row row : rows) {
            levels.computeIfAbsent(row.getLong("rule_id"), k -> new ArrayList<>()).add(toLevelRow(row));
        }
        return levels;
    }

    private Map<Long, Map<String, List<KafkaOutputTarget>>> loadLevelTargets(
        RuleKind kind, Set<Long> ruleIds, MetadataReadView readView, boolean fullDomain) {
        if (!fullDomain && ruleIds.isEmpty()) {
            return Map.of();
        }
        RowSet<Row> rows = fullDomain
            ? readView.connection().preparedQuery(SELECT_LEVEL_TARGETS)
            .execute(io.vertx.sqlclient.Tuple.of(kind.name())).await()
            : readView.connection().preparedQuery(SqlIn.expand(SELECT_LEVEL_TARGETS_BY_IDS, ruleIds.size()))
            .execute(prepend(kind.name(), ruleIds)).await();

        Map<Long, Map<String, List<KafkaOutputTarget>>> byRule = new HashMap<>();
        for (Row row : rows) {
            KafkaOutputTarget target = new KafkaOutputTarget(
                row.getLong("output_id"),
                row.getString("target_topic"),
                OutputFormat.valueOf(row.getString("format")));
            byRule.computeIfAbsent(row.getLong("rule_id"), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.getString("level_code"), k -> new ArrayList<>())
                .add(target);
        }
        return byRule;
    }

    private Map<Long, List<KafkaOutputTarget>> loadRouteTargets(Set<Long> ruleIds, MetadataReadView readView,
                                                                boolean fullDomain) {
        if (!fullDomain && ruleIds.isEmpty()) {
            return Map.of();
        }
        RowSet<Row> rows = fullDomain
            ? readView.connection().preparedQuery(SELECT_ROUTE_TARGETS).execute().await()
            : readView.connection().preparedQuery(SqlIn.expand(SELECT_ROUTE_TARGETS_BY_IDS, ruleIds.size()))
            .execute(SqlIn.tuple(ruleIds)).await();
        Map<Long, List<KafkaOutputTarget>> byRule = new HashMap<>();
        for (Row row : rows) {
            byRule.computeIfAbsent(row.getLong("rule_id"), k -> new ArrayList<>())
                .add(new KafkaOutputTarget(
                    row.getLong("output_id"),
                    row.getString("target_topic"),
                    OutputFormat.valueOf(row.getString("format"))));
        }
        return byRule;
    }

    private Map<Long, Set<Long>> loadBindings(Set<Long> ruleIds, MetadataReadView readView, boolean fullDomain,
                                              String selectAll, String selectByIds) {
        if (!fullDomain && ruleIds.isEmpty()) {
            return Map.of();
        }
        RowSet<Row> rows = fullDomain
            ? readView.connection().preparedQuery(selectAll).execute().await()
            : readView.connection().preparedQuery(SqlIn.expand(selectByIds, ruleIds.size()))
            .execute(SqlIn.tuple(ruleIds)).await();
        Map<Long, Set<Long>> bindings = new HashMap<>();
        for (Row row : rows) {
            bindings.computeIfAbsent(row.getLong("rule_id"), k -> new HashSet<>())
                .add(row.getLong("product_id"));
        }
        return bindings;
    }

    /**
     * {@code rule_kind = ?} 在 {@code IN (...)} 之前，因此占位符顺序是「先 kind 后 ids」。
     */
    private static io.vertx.sqlclient.Tuple prepend(String kind, Set<Long> ruleIds) {
        List<Object> values = new ArrayList<>(ruleIds.size() + 1);
        values.add(kind);
        values.addAll(ruleIds);
        return io.vertx.sqlclient.Tuple.from(values);
    }

    // ────────────────────────── 行映射 ──────────────────────────

    /**
     * 档位的中间形态：配置与脚本原文一起取出，装配时再拆成两份。
     *
     * <p>不直接产出 {@link LevelDefinition} 是因为脚本原文不属于配置模型 ——
     * {@code LevelDefinition} 要能被 admin 的校验器与前端复用，带着几 KB 的 Groovy 正文走一圈
     * 只会让每个引用它的地方都多背一份内存。
     */
    private record LevelRow(LevelDefinition definition, String conditionScript, String outputScript) {
    }

    private static LevelRow toLevelRow(Row row) {
        ConditionKind conditionKind = ConditionKind.valueOf(row.getString("condition_kind"));
        JsonObject threshold = parseJson(SqlJson.text(row, "threshold_config"));
        LevelDefinition definition = new LevelDefinition(
            row.getLong("level_id"),
            row.getString("level_code"),
            row.getInteger("severity"),
            conditionKind,
            threshold == null ? null : enumOrNull(ThresholdOperator.class, threshold.getString("operator")),
            threshold == null ? null : threshold.getDouble("threshold"),
            row.getLong("cooldown_millis"));
        return new LevelRow(definition, row.getString("condition_script"), row.getString("output_script"));
    }

    private static MetadataRawPatch.RuleRow toInstantRow(Row row, List<LevelRow> levels, Set<Long> productIds,
                                                         Map<String, List<KafkaOutputTarget>> levelTargets) {
        InstantRuleDefinition definition = new InstantRuleDefinition(
            row.getLong("rule_id"),
            row.getString("rule_code"),
            MessageType.fromCode(row.getString("message_type")),
            parseListener(SqlJson.text(row, "listener_config")),
            parseValue(SqlJson.text(row, "value_config")),
            EmitMode.valueOf(row.getString("emit_mode")),
            definitionsOf(levels),
            ErrorPolicy.valueOf(row.getString("error_policy")),
            row.getLong("revision"));
        return toRuleRow(definition, row, levels, productIds, levelTargets);
    }

    private static MetadataRawPatch.RuleRow toWindowRow(Row row, List<LevelRow> levels, Set<Long> productIds,
                                                        Map<String, List<KafkaOutputTarget>> levelTargets) {
        WindowRuleDefinition definition = new WindowRuleDefinition(
            row.getLong("rule_id"),
            row.getString("rule_code"),
            MessageType.fromCode(row.getString("message_type")),
            parseListener(SqlJson.text(row, "listener_config")),
            parseWindow(SqlJson.text(row, "window_config")),
            parseAggregate(SqlJson.text(row, "aggregate_config")),
            definitionsOf(levels),
            ErrorPolicy.valueOf(row.getString("error_policy")),
            row.getLong("revision"));
        return toRuleRow(definition, row, levels, productIds, levelTargets);
    }

    /**
     * 脚本按 {@code definition.levels()} 的顺序重排。
     *
     * <p>SQL 已按 severity 排序，构造器又排了一次，两者顺序理应一致 ——
     * 但这里<b>按 levelId 显式对齐</b>而不是依赖两处顺序巧合相同。
     * 错位不会抛任何异常：「危急档」会执行「预警档」的输出脚本，两者都能跑通。
     */
    private static MetadataRawPatch.RuleRow toRuleRow(RuleDefinition definition, Row row,
                                                      List<LevelRow> levels, Set<Long> productIds,
                                                      Map<String, List<KafkaOutputTarget>> levelTargets) {
        Map<Long, LevelRow> byLevelId = new HashMap<>(levels.size());
        for (LevelRow level : levels) {
            byLevelId.put(level.definition().levelId(), level);
        }
        List<MetadataRawPatch.LevelScripts> scripts = new ArrayList<>(definition.levels().size());
        for (LevelDefinition level : definition.levels()) {
            LevelRow source = byLevelId.get(level.levelId());
            if (source == null) {
                throw new IllegalStateException("档位脚本缺失: levelId=" + level.levelId());
            }
            scripts.add(new MetadataRawPatch.LevelScripts(source.conditionScript(), source.outputScript()));
        }
        return new MetadataRawPatch.RuleRow(
            definition,
            row.getLong("revision"),
            row.getString("script_sha256"),
            row.getString("filter_script"),
            scripts,
            productIds,
            levelTargets);
    }

    private static List<LevelDefinition> definitionsOf(List<LevelRow> levels) {
        return levels.stream().map(LevelRow::definition).toList();
    }

    private static ListenerConfig parseListener(String json) {
        if (json == null || json.isBlank()) {
            return ListenerConfig.ALL;
        }
        JsonArray identifiers = new JsonObject(json).getJsonArray("identifiers");
        if (identifiers == null || identifiers.isEmpty()) {
            return ListenerConfig.ALL;
        }
        Set<String> values = new HashSet<>(identifiers.size());
        for (int i = 0; i < identifiers.size(); i++) {
            values.add(identifiers.getString(i));
        }
        return new ListenerConfig(values);
    }

    private static ValueConfig parseValue(String json) {
        JsonObject obj = parseJson(json);
        if (obj == null) {
            return null;
        }
        return new ValueConfig(obj.getString("valueIdentifier"),
                               enumOrNull(ValueSource.class, obj.getString("valueSource")));
    }

    private static WindowConfig parseWindow(String json) {
        JsonObject obj = parseJson(json);
        if (obj == null) {
            throw new IllegalStateException("窗口规则缺少 window_config");
        }
        return new WindowConfig(
            enumOrNull(WindowType.class, obj.getString("type")),
            enumOrNull(TimeMode.class, obj.getString("timeMode")),
            obj.getLong("sizeMillis"),
            obj.getLong("advanceMillis"),
            obj.getLong("graceMillis"),
            obj.getLong("retentionMillis"),
            enumOrNull(StateScope.class, obj.getString("stateScope")));
    }

    private static AggregateConfig parseAggregate(String json) {
        JsonObject obj = parseJson(json);
        if (obj == null) {
            throw new IllegalStateException("窗口规则缺少 aggregate_config");
        }
        return new AggregateConfig(
            enumOrNull(AggregateType.class, obj.getString("type")),
            obj.getString("valueIdentifier"),
            enumOrNull(ValueSource.class, obj.getString("valueSource")));
    }

    private static JsonObject parseJson(String json) {
        return json == null || json.isBlank() ? null : new JsonObject(json);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        return name == null || name.isBlank() ? null : Enum.valueOf(type, name);
    }
}

package com.unisence.iot.rule.config;

import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.sdk.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 即时规则 / 窗口规则 + 档位模型的配置校验测试。
 * 对齐 harness/backend/business-nodes/rule-engine-module/rule-config-schema.md。
 *
 * <p>原先针对「窗口 × 聚合 × 触发 × firePolicy」组合矩阵的用例整体删除 ——
 * 那些非法组合现在<b>结构上不可表达</b>（两张表、两个 record），没有可断言的对象。
 * 替换为档位模型自身的约束：档位非空、severity 唯一、条件必填项、取值配置。
 */
class RuleConfigValidatorTest {

    private static final ThingModelSnapshot THING_MODEL = new ThingModelSnapshot(
        Map.of(
            "temperature", new PropertyDefinition("temperature", "温度", PropertyDataType.FLOAT, 1, "℃", 180),
            "powerSwitch", new PropertyDefinition("powerSwitch", "开关", PropertyDataType.BOOL, 2, null, 180)),
        Map.of());

    // ---------------- 合法配置 ----------------

    @Test
    @DisplayName("即时规则 + 单档阈值：最小合法规则")
    void acceptsInstantRule() {
        assertEquals(List.of(), validate(instantRule()));
    }

    @Test
    @DisplayName("即时规则 + 三档：同一信号的多档告警不再需要拆成三条规则")
    void acceptsMultiLevelInstantRule() {
        InstantRuleDefinition rule = new InstantRuleDefinition(
            10L, "temp-tiers", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new ValueConfig("temperature", ValueSource.PROPERTY),
            EmitMode.LEVEL_TRANSITION,
            List.of(threshold(1L, "MINOR", 30, ThresholdOperator.GT, 80.0),
                    threshold(2L, "MAJOR", 20, ThresholdOperator.GT, 95.0),
                    threshold(3L, "CRITICAL", 10, ThresholdOperator.GT, 100.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertEquals(List.of(), validate(rule));
    }

    @Test
    @DisplayName("档位按 severity 升序冻结：构造顺序不影响判档顺序")
    void sortsLevelsBySeverityAtConstruction() {
        InstantRuleDefinition rule = new InstantRuleDefinition(
            11L, "unsorted", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new ValueConfig("temperature", ValueSource.PROPERTY),
            EmitMode.LEVEL_TRANSITION,
            List.of(threshold(1L, "MINOR", 30, ThresholdOperator.GT, 80.0),
                    threshold(2L, "CRITICAL", 10, ThresholdOperator.GT, 100.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertEquals(List.of("CRITICAL", "MINOR"),
                     rule.levels().stream().map(LevelDefinition::levelCode).toList());
    }

    @Test
    @DisplayName("跳跃窗口 + AVG 聚合 + 阈值档位")
    void acceptsWindowRule() {
        WindowRuleDefinition rule = new WindowRuleDefinition(
            3L, "avg-high", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new WindowConfig(WindowType.HOPPING_TIME, TimeMode.EVENT_TIME,
                             300_000L, 60_000L, 30_000L, 400_000L, StateScope.DEVICE),
            new AggregateConfig(AggregateType.AVG, "temperature", ValueSource.PROPERTY),
            List.of(threshold(4L, "MAJOR", 20, ThresholdOperator.GT, 80.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertEquals(List.of(), validate(rule));
    }

    // ---------------- 档位 ----------------

    @Test
    @DisplayName("没有档位：规则永远不会产生输出")
    void rejectsRuleWithoutLevels() {
        InstantRuleDefinition rule = new InstantRuleDefinition(
            12L, "no-level", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new ValueConfig("temperature", ValueSource.PROPERTY),
            EmitMode.LEVEL_TRANSITION,
            List.of(), ErrorPolicy.DLQ_MESSAGE, 1L);

        assertHas(rule, RuleConfigErrorCode.LEVEL_CONFIG_INVALID, "levels");
    }

    @Test
    @DisplayName("severity 重复：判档命中哪个取决于排序稳定性，必须拒绝")
    void rejectsDuplicateSeverity() {
        InstantRuleDefinition rule = withLevels(
            threshold(1L, "A", 10, ThresholdOperator.GT, 80.0),
            threshold(2L, "B", 10, ThresholdOperator.GT, 90.0));

        assertHas(rule, RuleConfigErrorCode.LEVEL_CONFIG_INVALID, "levels.severity");
    }

    @Test
    @DisplayName("档位编码重复")
    void rejectsDuplicateLevelCode() {
        InstantRuleDefinition rule = withLevels(
            threshold(1L, "CRITICAL", 10, ThresholdOperator.GT, 80.0),
            threshold(2L, "CRITICAL", 20, ThresholdOperator.GT, 90.0));

        assertHas(rule, RuleConfigErrorCode.LEVEL_CONFIG_INVALID, "levels.levelCode");
    }

    @Test
    @DisplayName("severity 占用正常档保留值：规则将永远处于正常态")
    void rejectsNormalSeverity() {
        InstantRuleDefinition rule = withLevels(
            threshold(1L, "A", LevelDefinition.NORMAL_SEVERITY, ThresholdOperator.GT, 80.0));

        assertHas(rule, RuleConfigErrorCode.LEVEL_CONFIG_INVALID, "levels.severity");
    }

    @Test
    @DisplayName("阈值档位缺算子：只给 threshold 无法表达「低于」类规则")
    void rejectsThresholdWithoutOperator() {
        InstantRuleDefinition rule = withLevels(
            new LevelDefinition(1L, "A", 10, ConditionKind.THRESHOLD, null, 80.0, null));

        assertHas(rule, RuleConfigErrorCode.LEVEL_CONFIG_INVALID, "levels.operator");
    }

    @Test
    @DisplayName("窗口规则的档位用 SCRIPT 条件：聚合语义会被搬进脚本，不开放")
    void rejectsScriptConditionOnWindowRule() {
        WindowRuleDefinition rule = new WindowRuleDefinition(
            13L, "w-script", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new WindowConfig(WindowType.TUMBLING_TIME, TimeMode.PROCESSING_TIME,
                             300_000L, null, null, 400_000L, StateScope.DEVICE),
            new AggregateConfig(AggregateType.COUNT, null, null),
            List.of(new LevelDefinition(1L, "A", 10, ConditionKind.SCRIPT, null, null, null)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertHas(rule, RuleConfigErrorCode.FEATURE_NOT_AVAILABLE, "levels.conditionKind");
    }

    @Test
    @DisplayName("即时规则全部档位用 SCRIPT：不需要取值配置")
    void acceptsScriptOnlyInstantRuleWithoutValueConfig() {
        InstantRuleDefinition rule = new InstantRuleDefinition(
            14L, "script-only", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            null,
            EmitMode.LEVEL_TRANSITION,
            List.of(new LevelDefinition(1L, "A", 10, ConditionKind.SCRIPT, null, null, null)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertEquals(List.of(), validate(rule));
    }

    // ---------------- 即时规则取值配置 ----------------

    @Test
    @DisplayName("有阈值档位却没配取值：运行期不知道拿哪个字段比较")
    void rejectsThresholdLevelWithoutValueConfig() {
        InstantRuleDefinition rule = new InstantRuleDefinition(
            15L, "no-value", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            null,
            EmitMode.LEVEL_TRANSITION,
            List.of(threshold(1L, "A", 10, ThresholdOperator.GT, 80.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertHas(rule, RuleConfigErrorCode.VALUE_CONFIG_INVALID, "value.valueIdentifier");
    }

    @Test
    @DisplayName("对布尔属性做阈值比较：类型不匹配")
    void rejectsNonNumericValueConfig() {
        InstantRuleDefinition rule = new InstantRuleDefinition(
            16L, "bool-threshold", MessageType.PROPERTY,
            new ListenerConfig(Set.of("powerSwitch")),
            new ValueConfig("powerSwitch", ValueSource.PROPERTY),
            EmitMode.LEVEL_TRANSITION,
            List.of(threshold(1L, "A", 10, ThresholdOperator.GT, 0.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertHas(rule, RuleConfigErrorCode.IDENTIFIER_TYPE_MISMATCH, "value.valueIdentifier");
    }

    // ---------------- 窗口配置 ----------------

    @Test
    @DisplayName("retention 小于 size + grace：窗口会在关闭前被清理")
    void rejectsTooShortRetention() {
        WindowRuleDefinition rule = withWindow(
            new WindowConfig(WindowType.TUMBLING_TIME, TimeMode.EVENT_TIME,
                             300_000L, null, 30_000L, 100_000L, StateScope.DEVICE));

        assertHas(rule, RuleConfigErrorCode.RETENTION_TOO_SHORT, "window.retentionMillis");
    }

    @Test
    @DisplayName("EVENT_TIME 未配 grace：乱序行为未定义")
    void rejectsEventTimeWithoutGrace() {
        WindowRuleDefinition rule = withWindow(
            new WindowConfig(WindowType.TUMBLING_TIME, TimeMode.EVENT_TIME,
                             300_000L, null, null, 400_000L, StateScope.DEVICE));

        assertHas(rule, RuleConfigErrorCode.WINDOW_CONFIG_INVALID, "window.graceMillis");
    }

    @Test
    @DisplayName("advance 大于 size：窗口之间会漏数据")
    void rejectsAdvanceLargerThanSize() {
        WindowRuleDefinition rule = withWindow(
            new WindowConfig(WindowType.HOPPING_TIME, TimeMode.PROCESSING_TIME,
                             60_000L, 120_000L, null, 200_000L, StateScope.DEVICE));

        assertHas(rule, RuleConfigErrorCode.WINDOW_CONFIG_INVALID, "window.advanceMillis");
    }

    @Test
    @DisplayName("stateScope=PRODUCT 当前不开放")
    void rejectsProductScope() {
        WindowRuleDefinition rule = withWindow(
            new WindowConfig(WindowType.TUMBLING_TIME, TimeMode.PROCESSING_TIME,
                             300_000L, null, null, 400_000L, StateScope.PRODUCT));

        assertHas(rule, RuleConfigErrorCode.FEATURE_NOT_AVAILABLE, "window.stateScope");
    }

    // ---------------- 聚合配置 ----------------

    @Test
    @DisplayName("SUM 聚合缺 valueIdentifier")
    void rejectsSumWithoutValue() {
        WindowRuleDefinition rule = withAggregate(new AggregateConfig(AggregateType.SUM, null, null));

        assertHas(rule, RuleConfigErrorCode.AGGREGATE_CONFIG_INVALID, "aggregate.valueIdentifier");
    }

    @Test
    @DisplayName("对布尔属性做 AVG 聚合：类型不匹配")
    void rejectsNonNumericAggregate() {
        WindowRuleDefinition rule = withAggregate(
            new AggregateConfig(AggregateType.AVG, "powerSwitch", ValueSource.PROPERTY));

        assertHas(rule, RuleConfigErrorCode.IDENTIFIER_TYPE_MISMATCH, "aggregate.valueIdentifier");
    }

    @Test
    @DisplayName("引用物模型中不存在的属性")
    void rejectsUnknownIdentifier() {
        WindowRuleDefinition rule = withAggregate(
            new AggregateConfig(AggregateType.SUM, "humidity", ValueSource.PROPERTY));

        assertHas(rule, RuleConfigErrorCode.IDENTIFIER_NOT_IN_THING_MODEL, "aggregate.valueIdentifier");
    }

    // ---------------- 监听 ----------------

    @Test
    @DisplayName("心跳消息不可配置 identifiers")
    void rejectsIdentifiersOnHeartbeat() {
        InstantRuleDefinition rule = new InstantRuleDefinition(
            9L, "hb", MessageType.DEVICE_HEARTBEAT,
            new ListenerConfig(Set.of("temperature")),
            null,
            EmitMode.LEVEL_TRANSITION,
            List.of(new LevelDefinition(1L, "A", 10, ConditionKind.SCRIPT, null, null, null)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        assertHas(rule, RuleConfigErrorCode.LISTENER_CONFIG_INVALID, "listener.identifiers");
    }

    // ---------------- 汇总 ----------------

    @Test
    @DisplayName("一次返回全部违规，而不是首个即抛")
    void collectsAllViolations() {
        WindowRuleDefinition rule = new WindowRuleDefinition(
            17L, "broken", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new WindowConfig(WindowType.HOPPING_TIME, TimeMode.EVENT_TIME,
                             60_000L, 120_000L, null, 10L, StateScope.PRODUCT),
            new AggregateConfig(AggregateType.SUM, null, null),
            List.of(threshold(1L, "A", 10, ThresholdOperator.GT, 80.0),
                    threshold(2L, "B", 10, ThresholdOperator.GT, 90.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);

        List<RuleConfigViolation> violations = validate(rule);

        assertTrue(violations.size() >= 4,
                   "应同时报出 severity 重复、stateScope、advance、聚合取值等问题: " + violations);
    }

    @Test
    @DisplayName("配置校验错误码全部落在已登记的 5041-5069 段")
    void errorCodesStayInRegisteredRange() {
        for (RuleConfigErrorCode code : RuleConfigErrorCode.values()) {
            assertTrue(code.code() >= 5041 && code.code() <= 5069,
                       code + " 越出登记区间: " + code.code());
        }
    }

    // ---------------- 夹具 ----------------

    private static List<RuleConfigViolation> validate(RuleDefinition rule) {
        return RuleConfigValidator.validate(rule, THING_MODEL);
    }

    private static void assertHas(RuleDefinition rule, RuleConfigErrorCode code, String field) {
        List<RuleConfigViolation> violations = validate(rule);
        assertTrue(violations.stream().anyMatch(v -> v.code() == code && v.field().equals(field)),
                   "缺少 " + code + " @ " + field + ", 实际=" + violations);
    }

    private static LevelDefinition threshold(long levelId, String code, int severity,
                                             ThresholdOperator operator, double value) {
        return new LevelDefinition(levelId, code, severity, ConditionKind.THRESHOLD, operator, value, null);
    }

    private static InstantRuleDefinition instantRule() {
        return new InstantRuleDefinition(
            1L, "temp-high", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new ValueConfig("temperature", ValueSource.PROPERTY),
            EmitMode.LEVEL_TRANSITION,
            List.of(threshold(1L, "CRITICAL", 10, ThresholdOperator.GT, 100.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);
    }

    private static InstantRuleDefinition withLevels(LevelDefinition... levels) {
        InstantRuleDefinition base = instantRule();
        return new InstantRuleDefinition(base.ruleId(), base.ruleCode(), base.messageType(),
                                         base.listener(), base.value(), base.emitMode(), List.of(levels),
                                         base.errorPolicy(), base.revision());
    }

    private static WindowRuleDefinition windowRule() {
        return new WindowRuleDefinition(
            2L, "temp-burst", MessageType.PROPERTY,
            new ListenerConfig(Set.of("temperature")),
            new WindowConfig(WindowType.TUMBLING_TIME, TimeMode.PROCESSING_TIME,
                             300_000L, null, null, 400_000L, StateScope.DEVICE),
            new AggregateConfig(AggregateType.COUNT, null, null),
            List.of(threshold(1L, "MAJOR", 20, ThresholdOperator.GTE, 3.0)),
            ErrorPolicy.DLQ_MESSAGE, 1L);
    }

    private static WindowRuleDefinition withWindow(WindowConfig window) {
        WindowRuleDefinition base = windowRule();
        return new WindowRuleDefinition(base.ruleId(), base.ruleCode(), base.messageType(),
                                        base.listener(), window, base.aggregate(),
                                        base.levels(), base.errorPolicy(), base.revision());
    }

    private static WindowRuleDefinition withAggregate(AggregateConfig aggregate) {
        WindowRuleDefinition base = windowRule();
        return new WindowRuleDefinition(base.ruleId(), base.ruleCode(), base.messageType(),
                                        base.listener(), base.window(), aggregate,
                                        base.levels(), base.errorPolicy(), base.revision());
    }
}

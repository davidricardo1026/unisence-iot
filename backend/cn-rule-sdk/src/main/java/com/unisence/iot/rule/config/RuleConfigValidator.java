package com.unisence.iot.rule.config;

import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.sdk.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则配置校验器。
 *
 * <p>DDL 已按 ddl-conventions.md §4 移除全部 CHECK 约束，本类连同各枚举是这些取值的唯一裁决方。
 * admin 保存事务与 engine 快照加载共用它，避免「admin 存得进、engine 加载不了」。
 *
 * <h2>为什么比重设计前短了一大截</h2>
 * 原先有一张 {@code 窗口 × 聚合 × 触发 × firePolicy} 的组合矩阵，靠十余条互斥规则去拒绝
 * 笛卡尔积里的非法组合。那些判定<b>整体消失</b>了，不是被简化，而是被结构消灭：
 *
 * <ul>
 *   <li>「无窗口却配了窗口参数」—— 即时规则表里没有那两列；</li>
 *   <li>「有窗口却没配聚合」—— {@link WindowRuleDefinition} 的 aggregate 是必填字段；</li>
 *   <li>「触发类型与 firePolicy 不匹配」—— 两个枚举都删了，触发时机由档位跃迁决定。</li>
 * </ul>
 *
 * <p><b>结构上不可表达 > 校验器拒绝 > 运行期静默无效</b>，这是本轮重设计的主线。
 */
public final class RuleConfigValidator {

    private RuleConfigValidator() {
    }

    /**
     * @param thingModel 被绑定产品的物模型；为 null 时跳过 identifier 存在性与类型校验
     *                   （engine 加载快照时物模型另有校验路径，不必重复）
     * @return 空列表表示配置合法
     */
    public static List<RuleConfigViolation> validate(RuleDefinition rule, ThingModelSnapshot thingModel) {
        return validate(rule, thingModel, RuleWindowLimits.DEFAULT);
    }

    /**
     * @param windowLimits 窗口容量边界；调用方应传入 {@code app.rule.window.*} 绑定的实例，
     *                     使 admin 与 engine 用同一套边界判定
     */
    public static List<RuleConfigViolation> validate(RuleDefinition rule, ThingModelSnapshot thingModel,
                                                     RuleWindowLimits windowLimits) {
        List<RuleConfigViolation> violations = new ArrayList<>();

        validateListener(rule, thingModel, violations);
        validateLevels(rule, violations);

        // 按类别分派。switch 覆盖两个分支由编译器保证 ——
        // 新增第三类规则时这里编译期报错，而不是运行期漏掉一整套校验
        switch (rule) {
            case InstantRuleDefinition instant -> validateInstant(instant, thingModel, violations);
            case WindowRuleDefinition window -> {
                validateWindow(window, violations);
                validateWindowCapacity(window, windowLimits, violations);
                validateAggregate(window, thingModel, violations);
            }
        }

        return List.copyOf(violations);
    }

    // ---------------- 档位（两类规则共用）----------------

    /**
     * 档位是新模型的核心，因此校验也集中在这里。
     *
     * <p>{@code severity} 唯一是硬要求：判档按 severity 升序取第一个满足条件的档位，
     * 两个档位同 severity 时命中哪个取决于排序稳定性 —— 那会让同一份配置
     * 在不同实例上产生不同的告警级别，且不可能从日志看出来。
     */
    private static void validateLevels(RuleDefinition rule, List<RuleConfigViolation> violations) {
        List<LevelDefinition> levels = rule.levels();
        if (levels == null || levels.isEmpty()) {
            violations.add(RuleConfigViolation.of("levels", RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                  "规则至少要有一个档位，否则它永远不会产生任何输出"));
            return;
        }

        Set<Integer> severities = new HashSet<>(levels.size());
        Set<String> codes = new HashSet<>(levels.size());
        for (LevelDefinition level : levels) {
            if (level.severity() >= LevelDefinition.NORMAL_SEVERITY) {
                violations.add(RuleConfigViolation.of("levels.severity",
                                                      RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                      "severity 必须小于 " + LevelDefinition.NORMAL_SEVERITY
                                                          + "（该值保留给隐含的正常档），实际=" + level.severity()));
            }
            if (!severities.add(level.severity())) {
                violations.add(RuleConfigViolation.of("levels.severity",
                                                      RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                      "同一规则内 severity 必须唯一，重复值=" + level.severity()));
            }
            if (level.levelCode() == null || level.levelCode().isBlank()) {
                violations.add(RuleConfigViolation.of("levels.levelCode",
                                                      RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                      "档位编码必填 —— 它会作为输出 header 交给下游做分派"));
            } else if (!codes.add(level.levelCode())) {
                violations.add(RuleConfigViolation.of("levels.levelCode",
                                                      RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                      "同一规则内档位编码必须唯一，重复值=" + level.levelCode()));
            }
            validateLevelCondition(rule, level, violations);
            if (level.cooldownMillis() != null && level.cooldownMillis() < 0) {
                violations.add(RuleConfigViolation.of("levels.cooldownMillis",
                                                      RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                      "冷却时长不可为负, 实际=" + level.cooldownMillis()));
            }
        }
    }

    /**
     * 条件的必填项，以及 {@code SCRIPT} 的适用范围。
     *
     * <h2>为什么窗口规则不开放 SCRIPT 条件</h2>
     * 窗口档位比较的对象是<b>聚合结果</b>，而聚合结果已经是一个数值 ——
     * 用脚本重算等于把聚合语义搬进脚本，而在脚本里累计 count/sum 正是
     * {@link RuleFilter} 明令禁止的行为。真要按脚本判定，说明监控的不是聚合值，
     * 那本就该配成即时规则。
     */
    private static void validateLevelCondition(RuleDefinition rule, LevelDefinition level,
                                               List<RuleConfigViolation> violations) {
        ConditionKind kind = level.conditionKind();
        if (kind == null) {
            violations.add(RuleConfigViolation.of("levels.conditionKind",
                                                  RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                  "档位条件类型必填 (THRESHOLD / SCRIPT)"));
            return;
        }
        if (kind == ConditionKind.THRESHOLD) {
            if (level.operator() == null) {
                violations.add(RuleConfigViolation.of("levels.operator",
                                                      RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                      "阈值档位必须指定算子，只给 threshold 无法表达「低于」类规则"));
            }
            if (level.threshold() == null) {
                violations.add(RuleConfigViolation.of("levels.threshold",
                                                      RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                                                      "阈值档位必须指定 threshold"));
            }
            return;
        }
        if (rule instanceof WindowRuleDefinition) {
            violations.add(RuleConfigViolation.of("levels.conditionKind",
                                                  RuleConfigErrorCode.FEATURE_NOT_AVAILABLE,
                                                  "窗口规则的档位只支持 THRESHOLD：档位比较的是聚合结果，"
                                                      + "用脚本重算等于把聚合搬进脚本；按脚本判定请改用即时规则"));
        }
    }

    // ---------------- 即时规则 ----------------

    /**
     * 即时规则的取值配置：有阈值档位时必填，且必须指向数值型属性。
     *
     * <p>全部档位都是 {@code SCRIPT} 时不需要 —— 那时比较逻辑整个在脚本里，
     * 平台不需要知道监控的是哪个字段。
     */
    private static void validateInstant(InstantRuleDefinition rule, ThingModelSnapshot thingModel,
                                        List<RuleConfigViolation> violations) {
        if (rule.emitMode() == EmitMode.EVERY_MATCH) {
            for (LevelDefinition level : rule.levels()) {
                if (level.cooldownMillis() != null) {
                    violations.add(RuleConfigViolation.of(
                        "level[" + level.levelCode() + "].cooldownMillis",
                        RuleConfigErrorCode.LEVEL_CONFIG_INVALID,
                        "EVERY_MATCH 模式不支持冷却"));
                }
            }
        }

        boolean needsValue = rule.levels().stream()
            .anyMatch(level -> level.conditionKind() == ConditionKind.THRESHOLD);
        ValueConfig value = rule.value();

        if (!needsValue) {
            return;
        }
        if (value == null || !value.hasValue()) {
            violations.add(RuleConfigViolation.of("value.valueIdentifier",
                                                  RuleConfigErrorCode.VALUE_CONFIG_INVALID,
                                                  "存在阈值型档位时必须指定被监控的 valueIdentifier，"
                                                      + "否则运行期不知道拿哪个字段与阈值比较"));
            return;
        }
        if (value.valueSource() == null) {
            violations.add(RuleConfigViolation.of("value.valueSource",
                                                  RuleConfigErrorCode.VALUE_CONFIG_INVALID,
                                                  "配置 valueIdentifier 时必须指定 valueSource"));
            return;
        }
        if (mismatchesMessageType(rule.messageType(), value.valueSource())) {
            violations.add(RuleConfigViolation.of("value.valueSource",
                                                  RuleConfigErrorCode.VALUE_CONFIG_INVALID,
                                                  valueSourceMismatchMessage(rule.messageType(),
                                                                             value.valueSource())));
            return;
        }
        checkNumericProperty(thingModel, value.valueSource(), value.valueIdentifier(),
                             "value.valueIdentifier", "阈值比较", violations);
    }

    // ---------------- 窗口规则 ----------------

    private static void validateWindow(WindowRuleDefinition rule, List<RuleConfigViolation> violations) {
        WindowConfig window = rule.window();
        if (window == null) {
            violations.add(RuleConfigViolation.of("window", RuleConfigErrorCode.WINDOW_CONFIG_INVALID,
                                                  "窗口规则必须配置 window_config"));
            return;
        }
        if (window.type() == null) {
            violations.add(RuleConfigViolation.of("window.type", RuleConfigErrorCode.WINDOW_CONFIG_INVALID,
                                                  "窗口类型必填 (TUMBLING_TIME / HOPPING_TIME)"));
            return;
        }
        if (window.stateScope() == StateScope.PRODUCT) {
            violations.add(RuleConfigViolation.of("window.stateScope", RuleConfigErrorCode.FEATURE_NOT_AVAILABLE,
                                                  "stateScope=PRODUCT 会 repartition 并把整个产品汇聚到单个 task，当前版本不开放；"
                                                      + "产品级聚合需两阶段预聚合，另行立项"));
        }

        requirePositive(window.sizeMillis(), "window.sizeMillis", violations);

        if (window.type().requiresAdvance()) {
            requirePositive(window.advanceMillis(), "window.advanceMillis", violations);
            if (window.advanceMillis() != null && window.sizeMillis() != null
                && window.advanceMillis() > window.sizeMillis()) {
                violations.add(RuleConfigViolation.of("window.advanceMillis",
                                                      RuleConfigErrorCode.WINDOW_CONFIG_INVALID,
                                                      "推进步长大于窗口长度会漏掉窗口之间的数据: advance=" + window.advanceMillis()
                                                          + ", size=" + window.sizeMillis()));
            }
        }

        if (window.timeMode() != null && window.timeMode().requiresGrace() && window.graceMillis() == null) {
            violations.add(RuleConfigViolation.of("window.graceMillis",
                                                  RuleConfigErrorCode.WINDOW_CONFIG_INVALID,
                                                  "EVENT_TIME 必须显式配置迟到数据宽限期，否则乱序数据的处理行为未定义"));
        }

        requirePositive(window.retentionMillis(), "window.retentionMillis", violations);
        if (window.retentionMillis() != null
            && window.retentionMillis() < window.minimumRetentionMillis()) {
            violations.add(RuleConfigViolation.of("window.retentionMillis",
                                                  RuleConfigErrorCode.RETENTION_TOO_SHORT,
                                                  "保留时长 " + window.retentionMillis() + "ms 小于 size + grace = "
                                                      + window.minimumRetentionMillis() + "ms，窗口会在关闭前被清理"));
        }
    }

    /**
     * §十一的窗口容量边界。这些量在保存时全部可算，因此必须在这里拦下 ——
     * 放到运行时就成了「worker 状态内存撑爆才发现」，而那时受害的是同实例的其它规则。
     */
    private static void validateWindowCapacity(WindowRuleDefinition rule, RuleWindowLimits limits,
                                               List<RuleConfigViolation> violations) {
        WindowConfig window = rule.window();
        if (window == null) {
            return;
        }

        Long size = window.sizeMillis();
        if (size != null && size > limits.maxWindowMillis()) {
            violations.add(RuleConfigViolation.of("window.sizeMillis",
                                                  RuleConfigErrorCode.WINDOW_CAPACITY_EXCEEDED,
                                                  "窗口长度 " + size + "ms 超出上限 " + limits.maxWindowMillis()
                                                      + "ms；更长周期的累计属于报表，应查询时序数据库而非常驻窗口状态"));
        }

        Long grace = window.graceMillis();
        if (grace != null && grace > limits.maxGraceMillis()) {
            violations.add(RuleConfigViolation.of("window.graceMillis",
                                                  RuleConfigErrorCode.WINDOW_CAPACITY_EXCEEDED,
                                                  "迟到宽限期 " + grace + "ms 超出上限 " + limits.maxGraceMillis()
                                                      + "ms；迟到更久的数据按补录处理，不进实时窗口"));
        }

        Long advance = window.advanceMillis();
        if (advance == null) {
            return;
        }
        if (advance < limits.minAdvanceMillis()) {
            violations.add(RuleConfigViolation.of("window.advanceMillis",
                                                  RuleConfigErrorCode.WINDOW_CAPACITY_EXCEEDED,
                                                  "滑动步长 " + advance + "ms 小于下限 " + limits.minAdvanceMillis() + "ms"));
        }
        // advance > size 已由 validateWindow 以「漏数据」拒绝，这里只算真正的重叠场景
        if (size != null && advance > 0 && advance <= size) {
            long amplification = (size + advance - 1) / advance;
            if (amplification > limits.maxAmplification()) {
                violations.add(RuleConfigViolation.of("window.advanceMillis",
                                                      RuleConfigErrorCode.WINDOW_CAPACITY_EXCEEDED,
                                                      "窗口放大因子 size/advance = " + amplification + " 超出上限 "
                                                          + limits.maxAmplification() + "；一条消息会同时落入 " + amplification
                                                          + " 个窗口，状态与 state Topic 写入等比放大"));
            }
        }
    }

    private static void validateAggregate(WindowRuleDefinition rule, ThingModelSnapshot thingModel,
                                          List<RuleConfigViolation> violations) {
        AggregateConfig aggregate = rule.aggregate();
        if (aggregate == null || aggregate.type() == null) {
            violations.add(RuleConfigViolation.of("aggregate.type",
                                                  RuleConfigErrorCode.AGGREGATE_CONFIG_INVALID,
                                                  "窗口规则必须配置聚合类型，否则窗口状态无人消费"));
            return;
        }
        AggregateType type = aggregate.type();

        if (!type.requiresValue()) {
            if (aggregate.hasValue()) {
                violations.add(RuleConfigViolation.of("aggregate.valueIdentifier",
                                                      RuleConfigErrorCode.AGGREGATE_CONFIG_INVALID,
                                                      "聚合类型 " + type + " 不取值，不应配置 valueIdentifier"));
            }
            return;
        }

        if (!aggregate.hasValue()) {
            violations.add(RuleConfigViolation.of("aggregate.valueIdentifier",
                                                  RuleConfigErrorCode.AGGREGATE_CONFIG_INVALID,
                                                  "聚合类型 " + type + " 必须指定 valueIdentifier"));
            return;
        }
        if (aggregate.valueSource() == null) {
            violations.add(RuleConfigViolation.of("aggregate.valueSource",
                                                  RuleConfigErrorCode.AGGREGATE_CONFIG_INVALID,
                                                  "配置 valueIdentifier 时必须指定 valueSource"));
            return;
        }
        if (mismatchesMessageType(rule.messageType(), aggregate.valueSource())) {
            violations.add(RuleConfigViolation.of("aggregate.valueSource",
                                                  RuleConfigErrorCode.AGGREGATE_CONFIG_INVALID,
                                                  valueSourceMismatchMessage(rule.messageType(),
                                                                             aggregate.valueSource())));
            return;
        }
        checkNumericProperty(thingModel, aggregate.valueSource(), aggregate.valueIdentifier(),
                             "aggregate.valueIdentifier", "聚合 " + type, violations);
    }

    // ---------------- 监听 ----------------

    private static void validateListener(RuleDefinition rule, ThingModelSnapshot thingModel,
                                         List<RuleConfigViolation> violations) {
        ListenerConfig listener = rule.listener();
        MessageType messageType = rule.messageType();

        if (supportsIdentifier(messageType) && listener.matchesAll()) {
            violations.add(RuleConfigViolation.of("listener.identifiers",
                                                  RuleConfigErrorCode.LISTENER_CONFIG_INVALID,
                                                  "属性或事件规则至少选择一个产品模型项，不允许监听全部"));
            return;
        }
        if (!listener.matchesAll() && !supportsIdentifier(messageType)) {
            violations.add(RuleConfigViolation.of("listener.identifiers",
                                                  RuleConfigErrorCode.LISTENER_CONFIG_INVALID,
                                                  "消息类型 " + messageType.code() + " 没有 identifier，不可配置 identifiers"));
            return;
        }
        if (thingModel == null) {
            return;
        }
        for (String identifier : listener.identifiers()) {
            boolean exists = messageType == MessageType.PROPERTY
                ? thingModel.hasProperty(identifier)
                : thingModel.hasEvent(identifier);
            if (!exists) {
                violations.add(RuleConfigViolation.of("listener.identifiers",
                                                      RuleConfigErrorCode.IDENTIFIER_NOT_IN_THING_MODEL,
                                                      "物模型中不存在 identifier: " + identifier));
            }
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 取值必须指向物模型里存在的数值型属性。
     *
     * <p>事件参数不查物模型：事件参数的类型定义在事件定义里，
     * 而 {@code ThingModelSnapshot} 的属性视图查不到它 —— 用属性视图去查会得出
     * 「不存在」这个错误结论，把合法规则拒掉。
     */
    private static void checkNumericProperty(ThingModelSnapshot thingModel, ValueSource source,
                                             String identifier, String field, String usage,
                                             List<RuleConfigViolation> violations) {
        if (thingModel == null || source != ValueSource.PROPERTY) {
            return;
        }
        var property = thingModel.property(identifier);
        if (property == null) {
            violations.add(RuleConfigViolation.of(field,
                                                  RuleConfigErrorCode.IDENTIFIER_NOT_IN_THING_MODEL,
                                                  "物模型中不存在属性: " + identifier));
        } else if (!property.dataType().numeric()) {
            violations.add(RuleConfigViolation.of(field,
                                                  RuleConfigErrorCode.IDENTIFIER_TYPE_MISMATCH,
                                                  usage + " 要求数值型属性, " + property.identifier()
                                                      + " 的 dataType=" + property.dataType().code()));
        }
    }

    private static boolean supportsIdentifier(MessageType messageType) {
        return messageType == MessageType.PROPERTY || messageType == MessageType.EVENT;
    }

    /**
     * {@code valueSource} 与 {@code messageType} 必须同源：属性规则只能取属性值，事件规则只能取事件参数。
     *
     * <p><b>不一致时不是「取不到值」而是「永远取不到值」</b>：运行期
     * {@code RuleProcessor.instantValue} / {@code windowValue} 按 {@code valueSource} 分派到
     * {@code ctx.numberParam} 或 {@code ctx.numberValue}，走错分支恒返回 null，
     * 于是每条消息都被判为正常档 —— 规则存得进、编译得过、一条告警都不出，且无任何日志。
     * 这正是 MODULE_LOG 2026-08-02 归纳的「未实现却不拒绝的取值比功能缺失更糟」同型问题，
     * 因此在保存期拒绝，而不是留给运行期静默。
     *
     * <p>校验而非结构消除：{@code valueSource} 仍是持久化的显式事实，
     * 删掉它会让「这个值从哪来」只能靠 {@code messageType} 反推，
     * 未来若出现第三种取值面（如设备影子）就无处表达。前端则直接由
     * {@code messageType} 派生该字段，不给用户构造出不一致组合的机会。
     */
    private static boolean mismatchesMessageType(MessageType messageType, ValueSource valueSource) {
        return switch (messageType) {
            case PROPERTY -> valueSource != ValueSource.PROPERTY;
            case EVENT -> valueSource != ValueSource.EVENT_PARAM;
            // 其余消息类型不进入可配置规则链（保存接口只接受 property/event），此处不额外判定
            default -> false;
        };
    }

    private static String valueSourceMismatchMessage(MessageType messageType, ValueSource valueSource) {
        ValueSource expected = messageType == MessageType.EVENT
            ? ValueSource.EVENT_PARAM : ValueSource.PROPERTY;
        return "消息类型 " + messageType.code() + " 的取值只能来自 " + expected
            + "，实际配置为 " + valueSource + "：该组合在运行期取不到任何值，规则将永不触发";
    }

    private static void requirePositive(Number value, String field, List<RuleConfigViolation> violations) {
        if (value == null) {
            violations.add(RuleConfigViolation.of(field, RuleConfigErrorCode.WINDOW_CONFIG_INVALID,
                                                  field + " 必填"));
        } else if (value.longValue() <= 0) {
            violations.add(RuleConfigViolation.of(field, RuleConfigErrorCode.WINDOW_CONFIG_INVALID,
                                                  field + " 必须为正数, 实际=" + value));
        }
    }
}

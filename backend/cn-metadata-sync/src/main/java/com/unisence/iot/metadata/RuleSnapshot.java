package com.unisence.iot.metadata;

import com.unisence.iot.message.DeviceEventMessage;
import com.unisence.iot.message.DeviceMessage;
import com.unisence.iot.message.DevicePropertyMessage;
import com.unisence.iot.message.SequencedDeviceMessage;
import com.unisence.iot.message.type.MessageType;

import java.util.*;

/**
 * 规则运行时索引（metadata-sync-bus.md「规则缓存并发红线」）。
 *
 * <p>热路径要回答的问题是「这条消息该跑哪些规则」，因此索引直接建成
 * {@code productKey → messageType → RuleRoute}：消息线程只取一次 route 即可拿到即时、窗口与
 * identifier 预筛，不构造复合字符串 key，不做任何排序。
 *
 * <h2>为什么建两份索引而不是一份带类别标记的</h2>
 * 两类规则的执行时机与代价完全不同：即时规则零状态、逐条判档；窗口规则要累加、
 * 到期才结算。运行时本就<b>先跑完全部即时规则再跑窗口规则</b>
 * （即时规则的 {@code DROP_MESSAGE} 必须在窗口累加之前生效，否则「已累加进窗口才发现该丢」）。
 * 一份混合列表意味着每条消息都要遍历两遍并各自过滤 —— 而分类是构建期就确定的事实。
 *
 * <p>窗口 sequence 状态的准入由 identifier 索引预判；没有实际窗口候选的消息不访问状态 Store。
 *
 * <p><b>只能整体随根快照替换</b>。严禁单独替换规则引用、原地修改或加锁 ——
 * 一个业务事务可能同时改产品与规则，分开替换会让消息线程看到「新产品配旧规则」的组合。
 */
public final class RuleSnapshot {

    private static final RuleSnapshot EMPTY =
        new RuleSnapshot(Map.of(), Map.of(), Map.of(), Map.of());

    /**
     * {@code productKey → messageType → 路由}，两级 key 均复用消息与 enum 对象。
     */
    private final Map<String, Map<MessageType, RuleRoute>> routesByProductAndType;
    /**
     * {@code ruleKey} → 规则；增量收敛时按键增删。
     *
     * <p>键是 {@code I:12} / {@code W:12} 而不是裸 {@code ruleId} —— 两张规则表各有独立的
     * {@code AUTO_INCREMENT}，裸 id 会让即时规则 12 号覆盖窗口规则 12 号。
     */
    private final Map<String, CompiledRule> byKey;
    /**
     * {@code ruleId} → 窗口规则；窗口收割时从结构化 key 直接取得 ruleId 后据此回查。
     *
     * <p>这里用裸 {@code ruleId} 是安全的：只有窗口规则会产生窗口状态，
     * 因此这张表内不存在跨类别的 id 冲突。
     */
    private final Map<Long, CompiledWindowRule> windowById;
    /**
     * {@code ruleId} → 透传路由规则；不进入 {@link #byKey}，与即时/窗口规则独立。
     */
    private final Map<Long, CompiledRouteRule> routesById;

    private RuleSnapshot(Map<String, Map<MessageType, RuleRoute>> routesByProductAndType,
                         Map<String, CompiledRule> byKey,
                         Map<Long, CompiledWindowRule> windowById,
                         Map<Long, CompiledRouteRule> routesById) {
        this.routesByProductAndType = routesByProductAndType;
        this.byKey = byKey;
        this.windowById = windowById;
        this.routesById = routesById;
    }

    public static RuleSnapshot empty() {
        return EMPTY;
    }

    public static RuleSnapshot of(Map<String, CompiledRule> rulesByKey,
                                  Map<Long, ProductRuntimeMeta> productsById) {
        return of(rulesByKey, productsById, Map.of());
    }

    /**
     * 由全部启用规则与透传路由重建索引。
     *
     * <p>规则总量是低频、小体量的元数据（保护上限万级），因此增量收敛也走「改完 byKey 再整体重建索引」：
     * 重建一次是 O(规则数 × 绑定数) 的纯内存操作，远比维护双向增量索引容易验证。
     */
    public static RuleSnapshot of(Map<String, CompiledRule> rulesByKey,
                                  Map<Long, ProductRuntimeMeta> productsById,
                                  Map<Long, CompiledRouteRule> routesById) {
        if (rulesByKey.isEmpty() && routesById.isEmpty()) {
            return EMPTY;
        }
        Map<String, EnumMap<MessageType, MutableRoute>> routes = new HashMap<>();
        Map<Long, CompiledWindowRule> windowById = new HashMap<>();
        Map<String, EnumMap<MessageType, TreeMap<Long, RouteTarget>>> routeTargetsIndex = new HashMap<>();

        for (CompiledRule rule : rulesByKey.values()) {
            for (Long productId : rule.productIds()) {
                ProductRuntimeMeta product = productsById.get(productId);
                if (product == null) {
                    // 绑定指向已删除产品：跳过而不是失败，否则一条脏绑定会让整个候选根构建不出来
                    continue;
                }
                MutableRoute route = routes
                    .computeIfAbsent(product.productKey(), ignored -> new EnumMap<>(MessageType.class))
                    .computeIfAbsent(rule.definition().messageType(), ignored -> new MutableRoute());
                // switch 覆盖两个分支由编译器保证：新增第三类规则时这里编译期报错，
                // 而不是默默把它从索引里漏掉 —— 那表现为「规则配了但从不执行」
                switch (rule) {
                    case CompiledInstantRule i -> route.instantRules.add(i);
                    case CompiledWindowRule w -> route.windowRules.add(w);
                }
            }
            if (rule instanceof CompiledWindowRule w) {
                windowById.put(w.ruleId(), w);
            }
        }
        for (CompiledRouteRule routeRule : routesById.values()) {
            for (Long productId : routeRule.productIds()) {
                ProductRuntimeMeta product = productsById.get(productId);
                if (product == null) {
                    continue;
                }
                routes.computeIfAbsent(product.productKey(), ignored -> new EnumMap<>(MessageType.class))
                    .computeIfAbsent(routeRule.messageType(), ignored -> new MutableRoute());
                TreeMap<Long, RouteTarget> byOutput = routeTargetsIndex
                    .computeIfAbsent(product.productKey(), ignored -> new EnumMap<>(MessageType.class))
                    .computeIfAbsent(routeRule.messageType(), ignored -> new TreeMap<>());
                for (KafkaOutputTarget target : routeRule.targets()) {
                    RouteTarget incoming = new RouteTarget(
                        target.outputId(), target.targetTopic(), target.format(), routeRule.ruleId());
                    byOutput.merge(target.outputId(), incoming,
                                   (existing, next) -> existing.ruleId() <= next.ruleId() ? existing : next);
                }
            }
        }
        return new RuleSnapshot(freezeRoutes(routes, routeTargetsIndex),
                                Map.copyOf(rulesByKey), Map.copyOf(windowById), Map.copyOf(routesById));
    }

    /**
     * 无匹配规则时返回空列表，调用方无需判空。
     */
    public List<CompiledInstantRule> instantRulesFor(String productKey, MessageType messageType) {
        return routeFor(productKey, messageType).instantRules();
    }

    public List<CompiledWindowRule> windowRulesFor(String productKey, MessageType messageType) {
        return routeFor(productKey, messageType).windowRules();
    }

    /**
     * 该 {@code (productKey, messageType)} 下是否存在窗口规则；管理与诊断用途。
     */
    public boolean hasWindowRule(String productKey, MessageType messageType) {
        return !routeFor(productKey, messageType).windowRules().isEmpty();
    }

    /**
     * 本条消息是否真正命中至少一个窗口 listener；纯静态判断，不执行脚本。
     */
    public boolean hasWindowCandidate(String productKey, DeviceMessage message) {
        return routeFor(productKey, message.messageType()).hasWindowCandidate(message);
    }

    public RuleRoute routeFor(String productKey, MessageType messageType) {
        Map<MessageType, RuleRoute> byType = routesByProductAndType.get(productKey);
        return byType == null ? RuleRoute.EMPTY : byType.getOrDefault(messageType, RuleRoute.EMPTY);
    }

    /**
     * 该 {@code (productKey, messageType)} 下透传目标并集；无命中时返回 {@link List#of()} 单例。
     */
    public List<RouteTarget> routeTargetsFor(String productKey, MessageType messageType) {
        return routeFor(productKey, messageType).routeTargets();
    }

    /**
     * 窗口收割用：从结构化到期 key 取得 ruleId 后回查规则。
     *
     * @return null 表示规则已删除或已停用，该状态是孤儿，回收即可
     */
    public CompiledWindowRule windowRuleById(long ruleId) {
        return windowById.get(ruleId);
    }

    public Map<String, CompiledRule> byKey() {
        return byKey;
    }

    public Map<Long, CompiledRouteRule> routesById() {
        return routesById;
    }

    public int size() {
        return byKey.size();
    }

    private static Map<String, Map<MessageType, RuleRoute>> freezeRoutes(
        Map<String, EnumMap<MessageType, MutableRoute>> routes,
        Map<String, EnumMap<MessageType, TreeMap<Long, RouteTarget>>> routeTargetsIndex) {
        Map<String, Map<MessageType, RuleRoute>> frozen = new HashMap<>(routes.size());
        routes.forEach((productKey, byType) -> {
            EnumMap<MessageType, TreeMap<Long, RouteTarget>> targetsByType = routeTargetsIndex.get(productKey);
            EnumMap<MessageType, RuleRoute> frozenByType = new EnumMap<>(MessageType.class);
            byType.forEach((messageType, route) -> {
                route.instantRules.sort(Comparator.comparingLong(CompiledRule::ruleId));
                route.windowRules.sort(Comparator.comparingLong(CompiledRule::ruleId));
                TreeMap<Long, RouteTarget> merged = targetsByType == null ? null : targetsByType.get(messageType);
                List<RouteTarget> routeTargets = merged == null || merged.isEmpty()
                    ? List.of()
                    : List.copyOf(merged.values());
                frozenByType.put(messageType, new RuleRoute(
                    List.copyOf(route.instantRules), List.copyOf(route.windowRules),
                    compileListenerPlan(route.instantRules), compileListenerPlan(route.windowRules),
                    routeTargets));
            });
            frozen.put(productKey, Map.copyOf(frozenByType));
        });
        return Map.copyOf(frozen);
    }

    private static ListenerPlan compileListenerPlan(List<? extends CompiledRule> rules) {
        if (rules.size() > Long.SIZE) {
            throw new IllegalStateException("单 route 规则数超过 64，无法编译候选掩码: " + rules.size());
        }
        long wildcardMask = 0L;
        Map<String, Long> identifierMasks = new HashMap<>();
        for (int index = 0; index < rules.size(); index++) {
            CompiledRule rule = rules.get(index);
            long bit = 1L << index;
            var listener = rule.definition().listener();
            if (listener == null || listener.matchesAll()) {
                wildcardMask |= bit;
            } else {
                for (String identifier : listener.identifiers()) {
                    identifierMasks.merge(identifier, bit, (left, right) -> left | right);
                }
            }
        }
        return new ListenerPlan(wildcardMask, Map.copyOf(identifierMasks));
    }

    /**
     * 构建期编译的 route 执行计划。bit ordinal 与已排序规则列表下标一一对应，消息线程只做 OR 与 trailing-zero 扫描。
     */
    public record RuleRoute(List<CompiledInstantRule> instantRules,
                            List<CompiledWindowRule> windowRules,
                            ListenerPlan instantPlan,
                            ListenerPlan windowPlan,
                            List<RouteTarget> routeTargets) {
        private static final RuleRoute EMPTY = new RuleRoute(
            List.of(), List.of(), ListenerPlan.EMPTY, ListenerPlan.EMPTY, List.of());

        public RuleRoute {
            instantRules = List.copyOf(instantRules);
            windowRules = List.copyOf(windowRules);
            instantPlan = instantPlan == null ? ListenerPlan.EMPTY : instantPlan;
            windowPlan = windowPlan == null ? ListenerPlan.EMPTY : windowPlan;
            routeTargets = routeTargets == null ? List.of() : List.copyOf(routeTargets);
        }

        public long instantCandidateMask(DeviceMessage message) {
            return instantPlan.candidateMask(message);
        }

        public long windowCandidateMask(DeviceMessage message) {
            if (!(message instanceof SequencedDeviceMessage)) return 0L;
            return windowPlan.candidateMask(message);
        }

        public boolean hasWindowCandidate(DeviceMessage message) {
            return windowCandidateMask(message) != 0L;
        }
    }

    public record ListenerPlan(long wildcardMask, Map<String, Long> identifierMasks) {
        private static final ListenerPlan EMPTY = new ListenerPlan(0L, Map.of());

        public ListenerPlan {
            identifierMasks = identifierMasks == null ? Map.of() : Map.copyOf(identifierMasks);
        }

        long candidateMask(DeviceMessage message) {
            long mask = wildcardMask;
            return switch (message) {
                case DevicePropertyMessage property -> {
                    for (String identifier : property.values().keySet()) {
                        Long identifiers = identifierMasks.get(identifier);
                        if (identifiers != null) mask |= identifiers;
                    }
                    yield mask;
                }
                case DeviceEventMessage event -> {
                    Long identifiers = identifierMasks.get(event.identifier());
                    yield identifiers == null ? mask : mask | identifiers;
                }
                default -> mask;
            };
        }
    }

    private static final class MutableRoute {
        private final List<CompiledInstantRule> instantRules = new ArrayList<>();
        private final List<CompiledWindowRule> windowRules = new ArrayList<>();
    }
}

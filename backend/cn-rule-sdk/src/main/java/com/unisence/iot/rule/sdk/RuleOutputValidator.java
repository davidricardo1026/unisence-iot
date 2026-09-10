package com.unisence.iot.rule.sdk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * output 脚本返回值的二次校验（detailed-design.md §四.6）。
 * cn-service-engine 热路径与 cn-service-admin 的 compile/test 共用。
 */
public final class RuleOutputValidator {

    /**
     * 脚本不可使用的顶级键（detailed-design.md §六）。
     *
     * <p><b>限制的理由已经变了，别按旧注释理解</b>：输出信封已取消，Kafka value 就是脚本返回的
     * 这个 map，因此这些键不再存在「覆盖平台字段」的可能。保留限制是为了消除**歧义** ——
     * 这六项由平台以 Kafka header 权威给出，脚本若在 body 里写同名键，
     * 把 header 与 body 拉平的下游将无从判断以哪个为准，而 {@code msgId/ruleId/revision}
     * 恰恰是下游的幂等键，判错就是漏去重或误去重。
     *
     * <p>代价是脚本不能把 {@code deviceCode} 这类字段回显进 payload（那是个挺自然的写法）。
     * 权衡后仍保留：设备身份已在 Kafka key 与 header 里各有一份，回显没有信息增益。
     */
    public static final Set<String> RESERVED_KEYS = Set.of(
        "msgId", "productKey", "deviceCode", "ruleId", "revision", "schemaVersion");

    private RuleOutputValidator() {
    }

    /**
     * @throws RuleScriptException OUTPUT_KEY_TYPE / OUTPUT_RESERVED_KEY / OUTPUT_VALUE_TYPE / OUTPUT_TOO_LARGE
     */
    public static void validate(Map<String, Object> payload, RuleScriptLimits limits) {
        if (payload == null) {
            throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_RETURN_TYPE, "output 脚本返回值不可为 null");
        }
        Counter counter = new Counter(limits);
        walkMap(payload, 1, counter);
        if (counter.bytes > limits.maxOutputBytes()) {
            throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_TOO_LARGE,
                                          "输出估算字节数 " + counter.bytes + " 超出上限 " + limits.maxOutputBytes());
        }
    }

    private static void walkMap(Map<?, ?> map, int depth, Counter counter) {
        counter.checkDepth(depth);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_KEY_TYPE,
                                              "输出 Map 的键必须是 String, 实际=" + describe(entry.getKey()));
            }
            if (depth == 1 && RESERVED_KEYS.contains(key)) {
                throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_RESERVED_KEY,
                                              "输出不得覆盖平台可信信封字段: " + key);
            }
            counter.countField(key);
            walkValue(entry.getValue(), depth, counter);
        }
    }

    private static void walkValue(Object value, int depth, Counter counter) {
        switch (value) {
            case null -> counter.bytes += 4;
            case String text -> counter.bytes += text.getBytes(StandardCharsets.UTF_8).length;
            case Boolean ignored -> counter.bytes += 5;
            case Integer ignored -> counter.bytes += 11;
            case Long ignored -> counter.bytes += 20;
            case Double ignored -> counter.bytes += 24;
            case BigDecimal decimal -> counter.bytes += decimal.toPlainString().length();
            case Map<?, ?> nested -> walkMap(nested, depth + 1, counter);
            case List<?> list -> {
                counter.checkDepth(depth + 1);
                for (Object element : list) {
                    counter.countField(null);
                    walkValue(element, depth + 1, counter);
                }
            }
            default -> throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_VALUE_TYPE,
                                                     "输出值类型不可序列化: " + describe(value)
                                                         + "（允许 String/Boolean/Integer/Long/Double/BigDecimal/Map/List/null）");
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static final class Counter {

        private final RuleScriptLimits limits;
        private int fields;
        private int bytes;

        private Counter(RuleScriptLimits limits) {
            this.limits = limits;
        }

        private void checkDepth(int depth) {
            if (depth > limits.maxOutputDepth()) {
                throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_TOO_LARGE,
                                              "输出嵌套深度 " + depth + " 超出上限 " + limits.maxOutputDepth());
            }
        }

        private void countField(String key) {
            if (++fields > limits.maxOutputFields()) {
                throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_TOO_LARGE,
                                              "输出字段数超出上限 " + limits.maxOutputFields());
            }
            if (key != null) {
                bytes += key.getBytes(StandardCharsets.UTF_8).length;
            }
        }
    }
}

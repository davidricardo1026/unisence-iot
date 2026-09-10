package com.unisence.iot.rule.sdk;

/**
 * 脚本编译与执行限制。纯 Java 值对象：cn-common 的 sdk 子包不引入 Spring，由各服务自行绑定。
 *
 * <p>配置键 {@code app.rule.script.*}，cn-service-admin（保存校验）与 cn-service-engine（快照刷新）
 * 两侧必须逐项一致；不一致时以 engine 为准，因为 admin 放宽只会让规则保存成功却在运行时被拒。
 *
 * @param maxFilterScriptChars filter 脚本最大字符数
 * @param maxOutputScriptChars output 脚本最大字符数
 * @param maxAstNodes          单个脚本允许的 AST 节点总数
 * @param filterTimeoutMillis  filter 单次执行截止时间
 * @param outputTimeoutMillis  output 单次执行截止时间
 * @param maxOutputFields      输出 Map 递归展开后的字段总数
 * @param maxOutputDepth       输出 Map 最大嵌套深度，顶层为 1
 * @param maxOutputBytes       输出 payload 的 UTF-8 估算字节上限
 */
public record RuleScriptLimits(
    int maxFilterScriptChars,
    int maxOutputScriptChars,
    int maxAstNodes,
    long filterTimeoutMillis,
    long outputTimeoutMillis,
    int maxOutputFields,
    int maxOutputDepth,
    int maxOutputBytes) {

    /**
     * 定值（2026-07-29，capacity-benchmark.md §二），此前为「须经压测后固化」的初始值。
     *
     * <p>三处相对初始值的调整都有实测依据，改动前请先读 capacity-benchmark.md §1.1：
     * <ul>
     *   <li>{@code maxAstNodes} 2000 → 3000：实测 AST 密度 0.26–0.29 节点/字符，
     *       2000 会先于 {@code maxOutputScriptChars} 触发，用户写到字符上限却被一条看不见的限制拒绝</li>
     *   <li>{@code maxOutputFields} 64 → 128：宽产品的诊断型告警（detail 带全部属性值）
     *       合法且常见，64 会误拒</li>
     *   <li>{@code maxOutputBytes} 16384 → 8192：128 个数值字段仅 ≈ 4.4 KB，
     *       16384 事实上永不触发；降到 8192 后「字段数管结构、字节数管体积」才各自成立</li>
     * </ul>
     *
     * <p>两个超时<b>刻意维持原值</b>：实测 p99 仅 0.9 µs / 14.6 µs，而最差单次达 2.9 ms
     * ——差额几乎全是 GC 与 safepoint。超时是失控探测器，不是延迟预算；
     * 向 p99 收紧只会把 GC 停顿转成规则失败。延迟由候选规则数上限（50）保障。
     */
    public static final RuleScriptLimits DEFAULT =
        new RuleScriptLimits(4000, 8000, 3000, 20L, 50L, 128, 5, 8192);

    public RuleScriptLimits {
        requirePositive(maxFilterScriptChars, "maxFilterScriptChars");
        requirePositive(maxOutputScriptChars, "maxOutputScriptChars");
        requirePositive(maxAstNodes, "maxAstNodes");
        requirePositive(filterTimeoutMillis, "filterTimeoutMillis");
        requirePositive(outputTimeoutMillis, "outputTimeoutMillis");
        requirePositive(maxOutputFields, "maxOutputFields");
        requirePositive(maxOutputDepth, "maxOutputDepth");
        requirePositive(maxOutputBytes, "maxOutputBytes");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("app.rule.script." + name + " 必须为正数, 实际=" + value);
        }
    }
}

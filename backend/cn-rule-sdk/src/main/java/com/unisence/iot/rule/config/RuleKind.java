package com.unisence.iot.rule.config;

/**
 * 规则类别。
 *
 * <h2>为什么需要它，而模型本身已经用密封接口表达了类别</h2>
 * 密封接口只在<b>Java 类型系统内</b>有效。规则身份要穿过三个没有类型的边界：
 *
 * <ul>
 *   <li><b>数据库</b> —— 两张表各有独立的 {@code AUTO_INCREMENT}，
 *       即时规则 5 号与窗口规则 5 号会同时存在；</li>
 *   <li><b>运行时状态 key</b> —— 档位状态两类规则共用同一份 committed memory 与 state Topic；</li>
 *   <li><b>输出 Kafka header</b> —— 下游的幂等键是
 *       {@code msgId + ruleKind + ruleId + revision}。<b>少了 kind 就是真错误</b>：
 *       同一条上行同时触发即时 5 号与窗口 5 号时，两条本该独立的告警会被下游
 *       判成重复而丢掉一条。</li>
 * </ul>
 *
 * <p>换言之：类别在编译期由密封接口保证，在<b>序列化边界</b>由本枚举保证，两者缺一不可。
 */
public enum RuleKind {

    INSTANT("I"),
    WINDOW("W"),
    /**
     * 透传路由：不进 {@link RuleDefinition} 密封接口，独立 {@code CompiledRouteRule}。
     */
    ROUTE("R");

    private final String prefix;

    RuleKind(String prefix) {
        this.prefix = prefix;
    }

    /**
     * 运行时状态 key 与规则键的前缀。
     *
     * <p>取单字母而不是全名：档位状态 key 形如 {@code I:12:3:abc123:dev-001}，
     * 每条状态都要带它，而 worker 内存与 state Topic 的体积按字节算。
     */
    public String prefix() {
        return prefix;
    }

    /**
     * 全局唯一的规则键：{@code I:12} / {@code W:12}。
     */
    public String ruleKey(long ruleId) {
        return prefix + ':' + ruleId;
    }
}

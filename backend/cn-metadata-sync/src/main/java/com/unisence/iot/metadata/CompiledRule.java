package com.unisence.iot.metadata;

import com.unisence.iot.rule.compiler.CompiledRuleScript;
import com.unisence.iot.rule.config.RuleDefinition;
import com.unisence.iot.rule.config.RuleKind;
import com.unisence.iot.rule.sdk.RuleFilter;

import java.util.List;
import java.util.Set;

/**
 * 一条已编译、可直接执行的规则（metadata-sync-bus.md §6.1、「规则缓存并发红线」）。
 *
 * <p>编译产物只存在于本实例的 JVM 中，<b>绝不序列化到 Redis</b>：{@code Class} 对象绑定到
 * 特定的 {@code GroovyClassLoader}，跨进程既传不过去也不该传 —— 每个实例各自从 MySQL
 * 原文编译，是本设计不选主从复制的直接原因。
 *
 * <h2>为什么编译产物也拆成两类</h2>
 * 只拆 {@link RuleDefinition} 而让编译产物保持单一类型的话，
 * {@code RuleSnapshot.routeFor(...)} 返回的即时规则其定义仍是 {@code RuleDefinition}，
 * 调用方每次都要重新 {@code instanceof} 一遍 —— 索引明明已经按类别分好了，
 * 类型却把这个事实丢掉了。拆开之后 {@code CompiledWindowRule.definition()}
 * 静态类型就是 {@link com.unisence.iot.rule.config.WindowRuleDefinition}，
 * 窗口与聚合配置直接可取，不存在「取到 null」的分支。
 */
public sealed interface CompiledRule permits CompiledInstantRule, CompiledWindowRule {

    RuleDefinition definition();

    /**
     * 运行时修订号；参与缓存键、窗口状态与档位状态的隔离。
     */
    long revision();

    /**
     * 过滤 + 全部档位脚本规范化拼接后的摘要。
     */
    String scriptSha256();

    /**
     * 绑定的产品 ID；空集表示该规则当前无绑定，不会被任何消息命中。
     */
    Set<Long> productIds();

    /**
     * 规则级前置过滤脚本。<b>纯闸门</b>：只回答「这条消息与本规则相关吗」，
     * 不再兼任告警条件 —— 告警条件由档位表达。
     */
    CompiledRuleScript<RuleFilter> filter();

    /**
     * 按 severity 升序，与 {@code definition().levels()} 一一对应同序。
     */
    List<CompiledLevel> levels();

    default long ruleId() {
        return definition().ruleId();
    }

    default RuleKind kind() {
        return definition().kind();
    }

    /**
     * 全局唯一的规则键 {@code I:12} / {@code W:12}。
     *
     * <p>两张规则表各有独立的 {@code AUTO_INCREMENT}，单靠 {@code ruleId} 会撞 ——
     * 而撞了之后表现是下游把两条本该独立的告警判成重复，见 {@link RuleKind}。
     */
    default String ruleKey() {
        return definition().ruleKey();
    }

    /**
     * 缓存键 {@code ruleKey:revision:scriptSha256}：脚本内容变了键就变，不可能读到旧编译产物。
     */
    default String cacheKey() {
        return ruleKey() + ":" + revision() + ":" + scriptSha256();
    }
}

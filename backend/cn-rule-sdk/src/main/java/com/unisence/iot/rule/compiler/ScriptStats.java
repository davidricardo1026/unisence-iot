package com.unisence.iot.rule.compiler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次编译的静态检查统计（groovy-sdk-contract.md §8.4）。
 *
 * <p>它填补的是 {@code CompileResult} 一直填不出来的那几个字段：编译产物此前只带
 * cacheKey / class / compiledAt，AST 规模与被引用 identifier 都只在编译期存在、随即丢弃。
 *
 * @param astNodes              实际访问到的 AST 节点数；与 {@code max-ast-nodes} 同一口径，
 *                              因此可直接用来观察「离上限还有多远」
 * @param referencedIdentifiers 脚本中出现的字符串字面量，<b>是被引用 identifier 的保守超集</b>
 */
public record ScriptStats(int astNodes, List<String> referencedIdentifiers) {

    public ScriptStats {
        referencedIdentifiers = referencedIdentifiers == null
            ? List.of() : List.copyOf(referencedIdentifiers);
    }

    /**
     * 编译期收集器。
     *
     * <p>不做成 {@code RuleAstGuard} 的实例字段：customizer 由
     * {@code RuleSandbox.configuration(...)} 创建后<b>被同一个 GroovyClassLoader 跨编译复用</b>，
     * 存实例状态会让并发编译互相污染计数。
     */
    static final class Collector {

        private int astNodes;
        /**
         * 有序去重：同一字面量出现多次只记一次，且保留出现顺序便于排查
         */
        private final Set<String> literals = new LinkedHashSet<>();

        void countNode() {
            astNodes++;
        }

        /**
         * 记录字符串字面量。
         *
         * <p><b>刻意收集全部字符串字面量而不是「看起来像 identifier 的那些」</b>：
         * 本字段的用途是「物模型删属性时反查受影响规则」，
         * 这个方向上<b>宁可多报不可漏报</b> —— 多报只是让人多复核一条规则，
         * 漏报则会让一条实际引用了该属性的规则在属性删除后静默失效。
         *
         * <p>因此它是超集，不是精确的引用集合；调用方不得据此断言「规则一定引用了 X」。
         */
        void recordLiteral(String value) {
            if (value != null && !value.isBlank() && value.length() <= MAX_LITERAL_CHARS) {
                literals.add(value);
            }
        }

        ScriptStats toStats() {
            return new ScriptStats(astNodes, List.copyOf(literals));
        }
    }

    /**
     * 超过该长度的字面量几乎不可能是 identifier（物模型 identifier 上限 50），不必留存。
     */
    private static final int MAX_LITERAL_CHARS = 64;
}

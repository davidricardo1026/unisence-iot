package com.unisence.iot.metadata;

import com.unisence.iot.rule.config.LevelDefinition;
import com.unisence.iot.rule.config.RuleKind;

import java.nio.charset.StandardCharsets;

/**
 * 一个档位上恒定不变的六项输出 header 的 UTF-8 预编码字节。
 *
 * <p>在快照构建期算一次挂到 {@code CompiledLevel} 上；热路径只编码随消息变化的七项
 * （msgId / productKey / deviceCode / fireType / fromLevelCode / occurredAt / triggeredAt）。
 * 数组由本 record 独占，调用方只读、不得修改。
 *
 * @param ruleKind  {@code RuleKind.name()}
 * @param ruleId    规则 ID 十进制字符串
 * @param revision  规则修订号十进制字符串
 * @param ruleCode  规则业务编码
 * @param levelCode 档位编码
 * @param severity  严重度十进制字符串
 */
public record LevelHeaderBytes(
    byte[] ruleKind,
    byte[] ruleId,
    byte[] revision,
    byte[] ruleCode,
    byte[] levelCode,
    byte[] severity) {

    /**
     * 由已编译规则与档位定义预编码。
     *
     * @param rule  所属规则（提供 kind / ruleId / revision / ruleCode）
     * @param level 档位定义（提供 levelCode / severity）
     */
    public static LevelHeaderBytes of(CompiledRule rule, LevelDefinition level) {
        return of(rule.kind(), rule.ruleId(), rule.revision(), rule.definition().ruleCode(), level);
    }

    /**
     * 由规则身份字段与档位定义预编码。快照构建期 {@link CompiledRule} 尚未装配时使用。
     */
    public static LevelHeaderBytes of(RuleKind kind, long ruleId, long revision,
                                      String ruleCode, LevelDefinition level) {
        return new LevelHeaderBytes(
            utf8(kind.name()),
            utf8(Long.toString(ruleId)),
            utf8(Long.toString(revision)),
            utf8(ruleCode),
            utf8(level.levelCode()),
            utf8(Integer.toString(level.severity())));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

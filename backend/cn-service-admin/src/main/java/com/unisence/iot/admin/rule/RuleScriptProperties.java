package com.unisence.iot.admin.rule;

import com.unisence.iot.rule.sdk.RuleScriptLimits;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.rule.script.*}（application-configuration.md §3.1、groovy-sdk-contract.md §九）。
 *
 * <p><b>跨服务共享键空间</b>：admin 保存规则时编译校验、engine 刷新快照时编译，两侧读<b>同名键</b>
 * 并构造同一个纯 Java 值对象 {@link RuleScriptLimits}。
 *
 * <p>两个 {@code application.yml} 的该块必须逐项一致。值不一致时<b>以 engine 为准</b> ——
 * admin 放宽只会让规则保存成功却在运行时被拒，那是最难排查的一类不一致。
 */
@Data
@ConfigurationProperties(prefix = "app.rule.script")
public class RuleScriptProperties {

    private int maxFilterScriptChars = 4000;
    private int maxOutputScriptChars = 8000;
    private int maxAstNodes = 2000;
    private long filterTimeoutMillis = 20L;
    private long outputTimeoutMillis = 50L;
    private int maxOutputFields = 64;
    private int maxOutputDepth = 5;
    private int maxOutputBytes = 16384;

    /**
     * 越界校验落在 {@link RuleScriptLimits} 的紧凑构造器，配错即启动失败。
     */
    public RuleScriptLimits toLimits() {
        return new RuleScriptLimits(maxFilterScriptChars,
                                    maxOutputScriptChars,
                                    maxAstNodes,
                                    filterTimeoutMillis,
                                    outputTimeoutMillis,
                                    maxOutputFields,
                                    maxOutputDepth,
                                    maxOutputBytes);
    }
}

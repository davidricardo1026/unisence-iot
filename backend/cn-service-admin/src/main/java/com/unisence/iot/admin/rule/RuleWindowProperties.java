package com.unisence.iot.admin.rule;

import com.unisence.iot.rule.sdk.RuleWindowLimits;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.rule.window.*}（capacity-benchmark.md §3.1）。
 *
 * <p>与 {@link RuleScriptProperties} 同属跨服务共享键空间：admin 保存时按它拒绝超容量的窗口参数，
 * engine 加载快照时按同名键复核。两个 {@code application.yml} 的该块必须逐项一致，
 * 不一致时<b>以 engine 为准</b> —— admin 放宽只会让规则存得进却在 engine 侧加载失败。
 */
@Data
@ConfigurationProperties(prefix = "app.rule.window")
public class RuleWindowProperties {

    private long maxWindowMillis = 86_400_000L;
    private long minAdvanceMillis = 10_000L;
    private int maxAmplification = 12;
    private long maxGraceMillis = 600_000L;

    /**
     * 越界校验落在 {@link RuleWindowLimits} 的紧凑构造器，配错即启动失败。
     */
    public RuleWindowLimits toLimits() {
        return new RuleWindowLimits(maxWindowMillis, minAdvanceMillis, maxAmplification, maxGraceMillis);
    }
}

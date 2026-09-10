package com.unisence.iot.rule.config;

/**
 * 即时规则的输出节奏。窗口规则没有该选项：窗口输出必然由到期结算的档位跃迁驱动。
 */
public enum EmitMode {

    /**
     * 按 severity 升序判档，只在档位发生变化时执行输出脚本；有档位状态与可选冷却。告警语义。
     */
    LEVEL_TRANSITION,

    /**
     * 每条通过 filter 的消息按 severity 升序取第一个成立的档位，执行该档位输出脚本并发到其绑定 Topic。
     * 无状态：不读写档位状态、不评估冷却、不产生恢复；{@code fireType=MATCH}。用于平台侧加工后转出。
     */
    EVERY_MATCH
}

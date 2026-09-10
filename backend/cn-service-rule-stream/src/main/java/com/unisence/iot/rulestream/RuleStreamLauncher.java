package com.unisence.iot.rulestream;

import com.unisence.iot.common.core.profile.AppProfile;

/**
 * 进程入口（{@code mainClass}）。
 *
 * <p><b>存在的唯一理由是时序</b>：{@link RuleStreamBootstrap} 上的 {@code @Slf4j}
 * 会生成 {@code static final Logger} 字段，该字段在<b>类初始化时</b>就触发 Log4j2 启动 ——
 * 早于它 {@code main()} 里的任何一行代码。因此「按 profile 选日志配置」不可能写在那里。
 *
 * <p>本类<b>不得持有任何 Logger 字段，也不得 import 任何会初始化日志的类</b>，
 * 否则时序保证立刻失效，且失效形式是「日志静默退回默认 ERROR 级」——不会有任何报错。
 */
public final class RuleStreamLauncher {

    private RuleStreamLauncher() {
    }

    public static void main(String[] args) {
        AppProfile.initLogging();
        RuleStreamBootstrap.main(args);
    }
}

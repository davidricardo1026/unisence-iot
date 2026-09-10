package com.unisence.iot.rulewindow;

import com.unisence.iot.common.core.profile.AppProfile;

/**
 * 窗口规则静态进程入口；不做 ServiceLoader 扫描或运行时能力分支。
 *
 * <p>本类<b>不得持有任何 Logger 字段，也不得 import 任何会初始化日志的类</b>，
 * 否则时序保证立刻失效，且失效形式是「日志静默退回默认 ERROR 级」——不会有任何报错。
 */
public final class WindowRuleStreamLauncher {

    private WindowRuleStreamLauncher() {
    }

    public static void main(String[] args) {
        AppProfile.initLogging();
        WindowRuleStreamBootstrap.main(args);
    }
}

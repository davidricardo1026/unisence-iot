package com.unisence.iot.common.core.profile;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 运行环境 profile 与日志配置选择（architecture-features/logging-runtime.md §3.1）。
 *
 * <p><b>仅供非 Spring 的可执行服务使用</b>（{@code cn-service-engine} /
 * {@code cn-service-rule-stream}）。Spring Boot 服务走
 * {@code application-{profile}.yml} 里的 {@code logging.config}，
 * 因为它的 {@code Log4J2LoggingSystem} 会重新初始化日志、静默覆盖
 * {@code log4j2.configurationFile}。
 *
 * <p><b>本类刻意不持有任何 Logger 字段</b>：{@link #initLogging()} 必须在
 * Log4j2 完成初始化之前调用，而任何一个 {@code static final Logger} 字段的类初始化
 * 都会立刻触发 Log4j2 启动。调用方同样必须是无 Logger 的 launcher 类。
 */
public final class AppProfile {

    /**
     * 环境变量名；两类服务共用同一个，避免「Spring 用一个、非 Spring 用另一个」。
     */
    public static final String ENV_NAME = "APP_PROFILE";

    public static final String DEV = "dev";
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private AppProfile() {
    }

    /**
     * @return 规范化为小写的 profile；未设置时默认 {@code dev}
     * @throws IllegalStateException profile 不能安全地用于 classpath 资源文件名
     */
    public static String resolve() {
        String raw = System.getenv(ENV_NAME);
        if (raw == null || raw.isBlank()) {
            return DEV;
        }
        String profile = raw.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_NAME.matcher(profile).matches() || profile.contains("..")) {
            throw new IllegalStateException(
                ENV_NAME + " 必须以字母或数字开头，只能包含字母、数字、点、下划线或连字符，且不能包含 ..，当前为 \""
                    + raw + "\"");
        }
        return profile;
    }

    /**
     * 按 profile 指定 Log4j2 配置文件，**必须在任何 Logger 初始化之前调用**。
     *
     * <p>不依赖命令行 {@code -Dlog4j2.configurationFile}：漏传时 Log4j2 会退回
     * 「默认 ERROR 级 Console」，链路上什么都看不到且**没有任何报错** ——
     * 这正是本仓 2026-08-02 压测时踩到的形态。由代码指定则不可能漏。
     *
     * <p>已显式设置该属性时不覆盖，便于压测或排障临时指定别的配置。
     */
    public static void initLogging() {
        String key = "log4j2.configurationFile";
        if (System.getProperty(key) != null) {
            return;
        }
        String resource = "log4j2-" + resolve() + ".xml";
        if (AppProfile.class.getClassLoader().getResource(resource) == null) {
            throw new IllegalStateException("找不到日志配置 classpath:" + resource);
        }
        System.setProperty(key, resource);
    }
}

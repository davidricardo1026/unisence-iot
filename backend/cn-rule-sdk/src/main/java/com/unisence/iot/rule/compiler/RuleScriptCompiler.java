package com.unisence.iot.rule.compiler;

import com.unisence.iot.rule.sdk.*;
import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilationFailedException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则脚本编译器。cn-service-admin 保存校验与 cn-service-engine 快照刷新共用。
 *
 * <p><b>生命周期</b>：每次快照刷新新建一个实例，刷新完成后旧实例 {@link #close()}。
 * 每个实例持有自己的 {@link GroovyClassLoader}；<b>禁止复用同一个 ClassLoader 反复 parseClass</b>，
 * 否则规则反复保存会不断向 Metaspace 追加类而无法回收。
 *
 * <p>编译本身线程安全（GroovyClassLoader 内部同步），可在快照刷新时并行编译多条规则。
 */
public final class RuleScriptCompiler implements AutoCloseable {

    private static final String CODE_BASE = "/groovy/unisence-rule";

    /** 沙箱语义版本。<b>收紧黑名单或改变拦截行为时必须手动 +1</b>，否则指纹不会变，
     *  存量规则就无法被识别为「需重新校验」。 */
    /**
     * 沙箱行为版本。**改变拦截行为时必须递增** —— 它进 sandboxProfile 指纹，
     * 是存量规则「编译结论已过期、需重新校验」的唯一识别依据。
     *
     * <p>v2（2026-07-29）：注入 {@code @ThreadInterrupt}，脚本从「不可中断」变为「可中断」。
     */
    private static final int SANDBOX_VERSION = 2;

    private final RuleScriptLimits limits;
    private final GroovyClassLoader filterLoader;
    private final GroovyClassLoader outputLoader;
    private final String sandboxProfile;
    private final AtomicLong sequence = new AtomicLong();

    public RuleScriptCompiler(RuleScriptLimits limits) {
        this.limits = limits;
        this.sandboxProfile = buildSandboxProfile(limits);
        ClassLoader parent = new RuleSdkClassLoader(RuleScriptCompiler.class.getClassLoader());
        this.filterLoader = new GroovyClassLoader(parent,
                                                  RuleSandbox.configuration(RuleFilterScript.class, limits));
        this.outputLoader = new GroovyClassLoader(parent,
                                                  RuleSandbox.configuration(RuleOutputScript.class, limits));
    }

    /**
     * 当前 Groovy 运行时版本，写入 {@code compile_result.groovyVersion}。
     *
     * <p>由 SDK 暴露而不是调用方自取：Groovy 在本模块是 {@code implementation} 依赖，
     * 不向 {@code cn-service-admin} 传递，调用方编译期根本看不到 {@code GroovySystem}。
     * 而这个版本号必须记录 —— 换了 Groovy 版本后，旧规则的「编译通过」结论就不再可信。
     */
    public String groovyVersion() {
        return groovy.lang.GroovySystem.getVersion();
    }

    /**
     * @param cacheKey ruleId:revision:scriptSha256
     * @throws RuleScriptException SCRIPT_TOO_LONG / SCRIPT_COMPILE_FAILED / SCRIPT_FORBIDDEN_SYNTAX / SCRIPT_TOO_COMPLEX
     */
    public CompiledRuleScript<RuleFilter> compileFilter(String cacheKey, String script) {
        checkLength(script, limits.maxFilterScriptChars(), "filter");
        return compile(filterLoader, cacheKey, script, RuleFilter.class);
    }

    /**
     * 编译档位的条件脚本（{@code conditionKind=SCRIPT}）。
     *
     * <p>与 filter 共用 {@link RuleFilter} 接口和同一个 ClassLoader：两者的签名与约束完全相同
     * —— 都是「读上下文、返回 Boolean、不得有副作用」。为它单开一个接口与基类，
     * 换来的只是一个新名字和一份要同步维护的沙箱配置。
     *
     * <p>差别在<b>职责</b>而非能力：filter 是规则级闸门（这条消息与本规则相关吗），
     * condition 是档位级判据（相关的前提下，该报哪一档）。
     */
    public CompiledRuleScript<RuleFilter> compileCondition(String cacheKey, String script) {
        checkLength(script, limits.maxFilterScriptChars(), "condition");
        return compile(filterLoader, cacheKey, script, RuleFilter.class);
    }

    public CompiledRuleScript<RuleOutput> compileOutput(String cacheKey, String script) {
        checkLength(script, limits.maxOutputScriptChars(), "output");
        return compile(outputLoader, cacheKey, script, RuleOutput.class);
    }

    /**
     * 编译并带回静态检查统计。
     *
     * <p>{@code endCollect()} 必须在 {@code finally} 中调用：编译失败时同样要清掉 ThreadLocal，
     * 否则线程复用会把上一次的计数带进下一次编译。
     */
    private <T> CompiledRuleScript<T> compile(GroovyClassLoader loader, String cacheKey,
                                              String script, Class<T> expected) {
        RuleAstGuard.beginCollect();
        boolean collected = false;
        try {
            Class<? extends T> clazz = parse(loader, cacheKey, script, expected);
            ScriptStats stats = RuleAstGuard.endCollect();
            collected = true;
            return new CompiledRuleScript<T>(cacheKey, clazz, System.currentTimeMillis(),
                                             stats, sandboxProfile);
        } finally {
            // parse 抛异常时上面的 endCollect 没跑到，这里兜底清理，避免线程复用串数据
            if (!collected) {
                RuleAstGuard.endCollect();
            }
        }
    }

    /**
     * 沙箱指纹：语义版本 + 黑名单内容 + 脚本限制。
     *
     * <p>三者任一变化都会改变指纹，从而让存量规则的 {@code compile_result} 与当前沙箱可区分。
     * 只取版本号不够 —— 改了限制值却忘了升版本就会漏掉；只取黑名单也不够 ——
     * 拦截<b>行为</b>的变化（如新增一类 AST 访问）不体现在名单内容上，那正是 {@code SANDBOX_VERSION} 的职责。
     */
    private static String buildSandboxProfile(RuleScriptLimits limits) {
        String material = SANDBOX_VERSION + "|"
            + new java.util.TreeSet<>(RuleAstGuard.FORBIDDEN_METHODS) + "|"
            + limits;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return "v" + SANDBOX_VERSION + "-" + sb;
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明 JRE 被裁剪过
            throw new IllegalStateException("JRE 不支持 SHA-256，无法计算沙箱指纹", e);
        }
    }

    private <T> Class<? extends T> parse(GroovyClassLoader loader, String cacheKey,
                                         String script, Class<T> expected) {
        String className = "Rule_" + sanitize(cacheKey) + "_" + sequence.incrementAndGet();
        try {
            Class<?> parsed = loader.parseClass(script, className);
            if (!expected.isAssignableFrom(parsed)) {
                throw new RuleScriptException(RuleScriptErrorCode.SCRIPT_COMPILE_FAILED,
                                              "编译产物未实现 " + expected.getSimpleName() + ": " + parsed.getName());
            }
            return parsed.asSubclass(expected);
        } catch (CompilationFailedException e) {
            throw unwrap(e, new RuleScriptException(RuleScriptErrorCode.SCRIPT_COMPILE_FAILED,
                                                    "规则脚本编译失败: " + e.getMessage(), e));
        } catch (RuleScriptException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unwrap(e, new RuleScriptException(RuleScriptErrorCode.SCRIPT_COMPILE_FAILED,
                                                    "规则脚本编译异常: " + e.getMessage(), e));
        }
    }

    /**
     * {@link RuleAstGuard} 在编译期抛出的 RuleScriptException 可能被 Groovy 包进
     * CompilationFailedException 或 GroovyBugError，逐层剥出以保留精确错误码。
     */
    private static RuleScriptException unwrap(Throwable thrown, RuleScriptException fallback) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof RuleScriptException ruleScriptException) {
                return ruleScriptException;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return fallback;
    }

    private static void checkLength(String script, int max, String stage) {
        if (script == null || script.isBlank()) {
            throw new RuleScriptException(RuleScriptErrorCode.SCRIPT_COMPILE_FAILED,
                                          stage + " 脚本不可为空");
        }
        if (script.length() > max) {
            throw new RuleScriptException(RuleScriptErrorCode.SCRIPT_TOO_LONG,
                                          stage + " 脚本字符数 " + script.length() + " 超出上限 " + max);
        }
    }

    private static String sanitize(String cacheKey) {
        return cacheKey == null ? "anonymous" : cacheKey.replaceAll("[^A-Za-z0-9]", "_");
    }

    /**
     * 规则全部脚本规范化拼接后的 SHA-256 十六进制值，写入 {@code script_sha256}。
     *
     * <p>规范化统一换行符并去掉每行尾部空白，使「只改了缩进/换行」不会产生新的缓存键与窗口状态隔离。
     *
     * <p><b>顺序是摘要的一部分</b>：调用方必须按固定顺序传入（filter，然后按 severity 升序
     * 逐档的 condition 与 output）。顺序不定会让「只调换了两个档位的显示次序」也产生新摘要，
     * 从而白白丢弃一次编译缓存与全部窗口状态。
     *
     * @param scripts 脚本正文，允许含 null（表示该位置没有脚本，如阈值型档位没有条件脚本）
     */
    public static String scriptSha256(java.util.List<String> scripts) {
        StringBuilder canonical = new StringBuilder();
        if (scripts != null) {
            for (String script : scripts) {
                if (!canonical.isEmpty()) {
                    canonical.append("\n\0\n");
                }
                canonical.append(normalize(script));
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", e);
        }
    }

    private static String normalize(String script) {
        if (script == null) {
            return "";
        }
        return script.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map(RuleScriptCompiler::stripTrailing)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("")
            .strip();
    }

    private static String stripTrailing(String line) {
        return line.stripTrailing();
    }

    @Override
    public void close() {
        closeQuietly(filterLoader);
        closeQuietly(outputLoader);
    }

    private static void closeQuietly(GroovyClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            throw new IllegalStateException("关闭规则脚本 ClassLoader 失败", e);
        }
    }

    /**
     * 最后一道类可见性边界：只放行 JDK、Groovy 运行时与规则 SDK，
     * 挡掉业务 Service、数据源、Kafka / Vert.x / 时序数据库客户端等一切基础设施类。
     *
     * <p>JDK 与 Groovy 运行时必须保持可见，编译产物的字节码本身就依赖它们；
     * 真正的强隔离由 detailed-design.md §3.5 第 4 条的「独立低权限 engine 进程/容器」承担，
     * 本类只负责压缩同进程内的可达面。
     */
    private static final class RuleSdkClassLoader extends ClassLoader {

        /**
         * 对脚本可见的三个包：SDK 本身、脚本基类所在的 compiler 包
         * （编译产物继承 RuleFilterScript / RuleOutputScript，必须可加载），
         * 以及共享值域枚举包 —— {@code ctx.envelope().messageType()} 返回
         * {@code com.unisence.iot.message.type.MessageType}，挡掉它脚本就读不到消息类型。
         *
         * <p>本白名单先于 {@link #DENIED_PREFIXES} 生效，因此 {@code message.type.} 能从
         * {@code com.unisence.iot.message.} 这条黑名单里挖出来 —— 枚举是纯值域，
         * 而同包下的 {@code IotMessage} 系列携带原始 payload，必须保持不可见。
         * {@code rule.config} 是管理端配置模型，脚本无需也不应看见。
         */
        private static final String[] ALLOWED_SDK_PREFIXES = {
            "com.unisence.iot.rule.sdk.",
            "com.unisence.iot.rule.compiler.",
            "com.unisence.iot.message.type."
        };

        private static final String[] DENIED_PREFIXES = {
            "com.unisence.iot.rule.config.",
            // 线上消息模型：脚本只能看见 MessageEnvelope 投影。放行 IotMessage 就等于放行
            // DeviceCreateMessage.formData()，DeviceSnapshot 剔掉的 sensitive 字段会从这里漏回去
            // （device-message-contract.md §八）。MessageType 例外，见 ALLOWED_SDK_PREFIXES。
            "com.unisence.iot.message.",
            "com.unisence.iot.common.",
            "com.unisence.iot.admin.",
            "com.unisence.iot.engine.",
            "com.unisence.iot.driver.",
            "org.apache.kafka.",
            "io.vertx.",
            "org.apache.iotdb.",
            "io.greptime.",
            "org.springframework.",
            "com.baomidou.",
            "com.zaxxer.",
            "javax.sql.",
            "java.sql.",
            "java.net.",
            "java.nio.file."
        };

        private RuleSdkClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!isExplicitlyAllowed(name)) {
                for (String denied : DENIED_PREFIXES) {
                    if (name.startsWith(denied)) {
                        throw new ClassNotFoundException("规则脚本不可访问的类: " + name);
                    }
                }
            }
            return super.loadClass(name, resolve);
        }

        private static boolean isExplicitlyAllowed(String name) {
            for (String allowed : ALLOWED_SDK_PREFIXES) {
                if (name.startsWith(allowed)) {
                    return true;
                }
            }
            return false;
        }
    }
}

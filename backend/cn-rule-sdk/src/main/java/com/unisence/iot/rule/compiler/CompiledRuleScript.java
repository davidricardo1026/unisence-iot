package com.unisence.iot.rule.compiler;

import com.unisence.iot.rule.sdk.RuleScriptErrorCode;
import com.unisence.iot.rule.sdk.RuleScriptException;

/**
 * 编译产物。{@code scriptClass} 不可变、可跨线程共享并缓存进 RuleSnapshot；
 * <b>实例必须每次执行新建</b>，禁止缓存 {@link #newInstance()} 的返回值。
 *
 * @param cacheKey       ruleId:revision:scriptSha256（detailed-design.md §3.6）
 * @param stats          编译期静态检查统计。{@code referencedIdentifiers} 是<b>保守超集</b>，
 *                       调用方不得据此断言「规则一定引用了 X」，只能用于反查候选，见 {@link ScriptStats}
 * @param sandboxProfile 沙箱配置指纹。沙箱黑名单、拦截行为或脚本限制变更时它会变，
 *                       便于批量识别「编译结论已过期、需重新校验」的存量规则 ——
 *                       否则收紧沙箱后，旧规则会带着过期的「编译通过」结论继续运行
 */
public record CompiledRuleScript<T>(
    String cacheKey,
    Class<? extends T> scriptClass,
    long compiledAt,
    ScriptStats stats,
    String sandboxProfile) {

    /**
     * 无参构造器的按 Class 缓存。
     *
     * <p><b>缓存的是构造器，不是实例</b> —— 实例仍然每次新建，类注释那条禁令不受影响。
     *
     * <p>为什么值得缓存（hotpath-findings.md §18.6）：{@code Class.getDeclaredConstructor()}
     * 每次调用都要在构造器数组里线性查找并 <b>{@code copyConstructor} 复制一份新的
     * {@code Constructor} 对象</b>，而本方法在热路径上每条消息最多调三次
     * （filter + condition + output）。实测占 rule-stream 分配的 1.83%、CPU 的 0.55%。
     *
     * <p>用 {@link ClassValue} 而非 {@code Map<Class, Constructor>}：前者由 JVM 与类的生命周期
     * 绑定，类被卸载时条目自动回收 —— 规则改版会不断产生新的脚本类，用普通 Map 会拖住
     * 已废弃的 {@code ClassLoader} 不释放。
     */
    private static final ClassValue<java.lang.reflect.Constructor<?>> NO_ARG_CONSTRUCTORS =
        new ClassValue<>() {
            @Override
            protected java.lang.reflect.Constructor<?> computeValue(Class<?> type) {
                try {
                    return type.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    // 返回 null 而非抛出：让失败仍从 newInstance 抛 RuleScriptException，
                    // 错误码与消息与缓存前完全一致
                    return null;
                }
            }
        };

    @SuppressWarnings("unchecked")
    public T newInstance() {
        java.lang.reflect.Constructor<?> constructor = NO_ARG_CONSTRUCTORS.get(scriptClass);
        if (constructor == null) {
            throw new RuleScriptException(RuleScriptErrorCode.SCRIPT_RUNTIME_ERROR,
                                          "规则脚本实例化失败: cacheKey=" + cacheKey
                                              + "（缺少无参构造器）");
        }
        try {
            return (T) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuleScriptException(RuleScriptErrorCode.SCRIPT_RUNTIME_ERROR,
                                          "规则脚本实例化失败: cacheKey=" + cacheKey, e);
        }
    }
}

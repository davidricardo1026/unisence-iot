package com.unisence.iot.rule.compiler;

import com.unisence.iot.rule.sdk.MessageContext;
import com.unisence.iot.rule.sdk.RuleFilter;
import com.unisence.iot.rule.sdk.RuleScriptErrorCode;
import com.unisence.iot.rule.sdk.RuleScriptException;
import groovy.lang.Script;

/**
 * filter_script 的编译基类，由 {@code CompilerConfiguration.setScriptBaseClass} 指定。
 *
 * <p><b>非线程安全</b>：实例持有本次执行的上下文，每条消息必须新建一个实例，用完即弃。
 */
public abstract class RuleFilterScript extends Script implements RuleFilter {

    private MessageContext ctx;

    /**
     * 脚本正文通过属性名 {@code ctx} 访问；因是基类属性而非 Binding 变量，@CompileStatic 可静态解析。
     */
    public MessageContext getCtx() {
        return ctx;
    }

    /**
     * 声明为 final，脚本无法覆盖入口方法（沙箱另行禁止方法定义，此处是第二道锁）。
     *
     * <p>严格判定 Boolean，<b>不接受 Groovy 真值性</b>：Groovy 中 ""/0/[]/null 均为假值，
     * 若放行真值性转换，一个返回空字符串的表达式会被静默判为「不匹配」。
     */
    @Override
    public final boolean filter(MessageContext ctx) {
        this.ctx = ctx;
        Object result = run();
        if (!(result instanceof Boolean matched)) {
            throw new RuleScriptException(RuleScriptErrorCode.FILTER_RETURN_TYPE,
                                          "filter 脚本必须返回 Boolean, 实际=" + (result == null ? "null" : result.getClass().getName()));
        }
        return matched;
    }
}

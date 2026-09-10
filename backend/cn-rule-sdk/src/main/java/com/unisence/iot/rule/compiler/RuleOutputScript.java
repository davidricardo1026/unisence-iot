package com.unisence.iot.rule.compiler;

import com.unisence.iot.rule.sdk.MessageContext;
import com.unisence.iot.rule.sdk.RuleOutput;
import com.unisence.iot.rule.sdk.RuleScriptErrorCode;
import com.unisence.iot.rule.sdk.RuleScriptException;
import com.unisence.iot.rule.sdk.TriggerResult;
import groovy.lang.Script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * output_script 的编译基类。<b>非线程安全</b>，实例仅供一次执行。
 */
public abstract class RuleOutputScript extends Script implements RuleOutput {

    private MessageContext ctx;
    private TriggerResult trigger;

    public MessageContext getCtx() {
        return ctx;
    }

    public TriggerResult getTrigger() {
        return trigger;
    }

    @Override
    public final Map<String, Object> output(MessageContext ctx, TriggerResult trigger) {
        this.ctx = ctx;
        this.trigger = trigger;
        Object result = run();
        if (!(result instanceof Map<?, ?> map)) {
            throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_RETURN_TYPE,
                                          "output 脚本必须返回 Map, 实际=" + (result == null ? "null" : result.getClass().getName()));
        }
        // 复制成平台自有的 LinkedHashMap：脚本返回的实例可能是 Groovy 的可变 Map，
        // 且键类型未经检查；此处一次性完成键类型校验与脱离脚本引用。
        Map<String, Object> payload = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new RuleScriptException(RuleScriptErrorCode.OUTPUT_KEY_TYPE,
                                              "输出 Map 的键必须是 String, 实际="
                                                  + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            payload.put(key, normalize(entry.getValue()));
        }
        return payload;
    }

    /**
     * GString 必须在校验前归一为 String，否则会把序列化失败推迟到 Kafka 事务里。
     * 递归处理嵌套结构：{@code [detail: [msg: "temp ${v}"]]} 这类写法很常见。
     * 深度与字段数由 RuleOutputValidator 随后统一把关，此处不重复限制。
     */
    private static Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case CharSequence text -> text.toString();
            case Map<?, ?> nested -> {
                Map<Object, Object> copy = new LinkedHashMap<>(nested.size());
                for (Map.Entry<?, ?> entry : nested.entrySet()) {
                    copy.put(normalize(entry.getKey()), normalize(entry.getValue()));
                }
                yield copy;
            }
            case List<?> list -> list.stream().map(RuleOutputScript::normalize).toList();
            default -> value;
        };
    }
}

package com.unisence.iot.rule.sdk;

import java.util.Map;

/**
 * 输出器：把上下文与触发结果映射为业务 payload。实现由 Groovy 脚本编译产生。
 *
 * <p>只返回 payload；topic、broker、key、serializer 与 headers 全部由平台配置决定，
 * 脚本不得指定，也不得自行发送 Kafka 消息。
 */
@FunctionalInterface
public interface RuleOutput {

    /**
     * @param ctx     本次消息的只读上下文，非 null
     * @param trigger 本次触发结果，非 null
     * @return payload map；返回 null 视为非法
     * @throws RuleScriptException 返回值类型非法、超出输出限制或执行超时
     */
    Map<String, Object> output(MessageContext ctx, TriggerResult trigger);
}

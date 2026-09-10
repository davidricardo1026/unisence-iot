package com.unisence.iot.rule.sdk;

/**
 * 过滤器：针对单条标准消息的无状态条件判定。实现由 Groovy 脚本编译产生。
 *
 * <p>禁止读写窗口状态、自行累计 count/sum、发起网络/文件/数据库调用或产生任何外部副作用。
 */
@FunctionalInterface
public interface RuleFilter {

    /**
     * @param ctx 本次消息的只读上下文，非 null
     * @return true 表示进入窗口/聚合阶段；false 表示当前规则就此结束，且不得创建或更新窗口状态
     * @throws RuleScriptException 脚本返回值类型非法、取值失败或执行超时
     */
    boolean filter(MessageContext ctx);
}

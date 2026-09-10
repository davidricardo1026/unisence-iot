package com.unisence.iot.metadata;

import com.unisence.iot.rule.config.OutputFormat;

import java.nio.charset.StandardCharsets;

/**
 * 透传路由的热路径视图：一个 {@code (productKey, messageType)} 下所有 {@code ROUTE} 规则目标的并集中的一项。
 *
 * <p>多条路由规则命中同一目标时的 {@code outputId} 去重在快照构建期完成，热路径只做 for-each；
 * {@code ruleId} 取命中该目标的最小规则 ID，{@code ruleIdHeaderBytes} 为其 UTF-8 预编码，
 * 直接作为输出 header {@code ruleId} 的值。
 *
 * @param outputId          输出定义 ID（并集内唯一）
 * @param targetTopic       静态精确 Topic
 * @param format            value 编码；{@code MESSAGEPACK} 直接复用上行字节，{@code JSON} 每条最多编码一次
 * @param ruleId            命中该目标的最小 ROUTE 规则 ID
 * @param ruleIdHeaderBytes {@code Long.toString(ruleId)} 的 UTF-8 字节
 */
public record RouteTarget(
    long outputId,
    String targetTopic,
    OutputFormat format,
    long ruleId,
    byte[] ruleIdHeaderBytes) {

    public RouteTarget(long outputId, String targetTopic, OutputFormat format, long ruleId) {
        this(outputId, targetTopic, format, ruleId, Long.toString(ruleId).getBytes(StandardCharsets.UTF_8));
    }
}

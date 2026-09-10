package com.unisence.iot.rule.sdk;

/**
 * 窗口聚合器的类型化产出。不暴露运行时的状态后端或累加器实现。
 *
 * <p>不属于当前 {@link AggregateType} 的字段不作为有效结果：引用类型为 null，原始类型
 * {@code count} 为 0。COUNT/AVG 的 {@code count} 才表示窗口样本数；即时规则固定为 1。
 *
 * @param type       聚合类型；<b>null 表示本次输出不来自聚合</b>（即时规则）
 * @param count      COUNT/AVG 的样本数；即时规则为 1；其他聚合类型为 0
 * @param firstAt    first 对应的 occurredAt，epoch millis
 * @param lastAt     last 对应的 occurredAt，epoch millis
 * @param changeRate (last - first) / (lastAt - firstAt) * 1000，单位「值/秒」
 */
public record AggregateResult(
    AggregateType type,
    long count,
    Double sum,
    Double min,
    Double max,
    Double avg,
    Double first,
    Double last,
    Long firstAt,
    Long lastAt,
    Double changeRate) {

    /**
     * 即时规则的聚合视图：单条消息即一次计算，没有聚合可言。
     *
     * <p>{@code type} 为 null 是<b>事实陈述</b>而不是占位 —— 脚本读到
     * {@code trigger.avg()} 为 null 时，正确的解读是「这条规则没有均值这个概念」。
     * 给一个 {@code NONE} 枚举值反而会让人以为「有聚合，只是类型叫 NONE」。
     */
    public static AggregateResult none() {
        return new AggregateResult(null, 1L,
                                   null, null, null, null, null, null, null, null, null);
    }

    public static AggregateResult ofCount(long count) {
        return new AggregateResult(AggregateType.COUNT, count,
                                   null, null, null, null, null, null, null, null, null);
    }

    /**
     * 档位阈值判定使用的数值结果：按聚合类型取对应字段。
     *
     * <p>{@code type} 为 null（即时规则）时返回 null —— 即时规则的比较对象来自
     * {@code value_config} 指向的消息字段，不走这里。
     */
    public Double numericResult() {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case COUNT -> (double) count;
            case SUM -> sum;
            case MIN -> min;
            case MAX -> max;
            case AVG -> avg;
            case FIRST -> first;
            case LAST -> last;
            case CHANGE_RATE -> changeRate;
        };
    }
}

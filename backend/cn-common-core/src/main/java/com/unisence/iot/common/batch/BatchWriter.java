package com.unisence.iot.common.batch;

import java.util.List;

/**
 * 批量写入器（batch-write-strategy.md §四）。
 *
 * <p>这是「单条写改批量写」这个优化的<b>全部内容</b>，也是唯一每条写入链路都需要的一层：
 * 给一批数据，尽量少的往返写完，逐项报告结果。
 *
 * <p><b>无状态、无线程、无生命周期、无重试策略</b> —— 因此它既可以被任意线程直接调用
 * （数据源本来就成批时，例如 Kafka 一次 poll、Redis Stream 一次 {@code XREADGROUP}），
 * 也可以被队列累积器驱动（数据一条条来时）。把队列和线程焊进批量写，会让前一种场景
 * 被迫接受后一种场景才需要付出的代价 —— 容量、丢弃、停机排空全部来自队列，
 * 没有一个来自批量写本身。
 *
 * <h2>实现方必须遵守</h2>
 * <ol>
 *   <li><b>不抛业务异常</b>：把异常归类成 {@link BatchResult#retryable} 还是
 *       {@link BatchResult#poison}，只有实现方知道 —— 它才分得清「主键冲突」与「连接超时」。
 *       向上抛异常等于把这个判断推给不掌握信息的调用方，结果就是一律无限重试；</li>
 *   <li><b>幂等</b>：{@code retryable} 的项一定会被重写，实现必须容忍重复写入
 *       （唯一键、fencing 条件、{@code INSERT ... ON DUPLICATE KEY}）；</li>
 *   <li><b>一次往返</b>：用 {@code executeBatch} / pipeline / Tablet，
 *       禁止在实现内部逐条循环写 —— 那样这一层就白做了。</li>
 * </ol>
 */
@FunctionalInterface
public interface BatchWriter<T> {

    /**
     * @param items 待写入的一批；调用方保证非空
     * @return 逐项归类的结果，三段之和应等于 {@code items.size()}
     */
    BatchResult<T> write(List<T> items);
}

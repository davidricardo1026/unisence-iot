package com.unisence.iot.engine.verticle;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 持续性故障下的错误日志抑制器。
 *
 * <p><b>存在的理由是一次实测</b>（`hotpath-findings.md` H16）：Redis 停机 45 秒期间，
 * transition 排空循环刷出 <b>300 万行</b>日志、文件涨到 <b>6.7 GB</b>。
 * 在容器里这会把「一个依赖不可用」升级成「磁盘写满、整机不可用」——
 * 而且真正需要人读的那条首次错误，会被后续几百万条完全相同的重复淹没。
 *
 * <p>策略：<b>首次立即打全栈</b>（诊断需要它），随后进入静默窗口，
 * 窗口结束时补一条汇总说明期间被抑制了多少条。故障恢复后调用 {@link #reset()}
 * 使下一次故障重新享有「首次立即打」的待遇。
 *
 * <p><b>刻意不做成通用日志框架能力</b>：Log4j2 的 {@code BurstFilter} 按 appender 配置，
 * 作用域太粗（会连带抑制无关的错误），而这里要抑制的是<b>特定循环的特定故障</b>。
 *
 * <p>本类线程安全，可从多个 shard 工作线程并发调用。
 */
public final class ThrottledErrorLog {

    private final Logger log;
    private final long windowMs;
    private final AtomicLong nextLogAt = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();

    /**
     * @param windowMs 静默窗口。取值应当 ≥ 该循环的退避周期，否则退避一次就打一条，抑制形同虚设
     */
    public ThrottledErrorLog(Logger log, long windowMs) {
        this.log = log;
        this.windowMs = windowMs;
    }

    /**
     * 记一条错误；窗口内的重复只累计计数不落盘。
     */
    public void error(String message, Throwable t) {
        long now = System.currentTimeMillis();
        long next = nextLogAt.get();
        if (now < next) {
            suppressed.incrementAndGet();
            return;
        }
        if (!nextLogAt.compareAndSet(next, now + windowMs)) {
            // 并发下已有别的线程拿到本窗口的名额，本条计入抑制
            suppressed.incrementAndGet();
            return;
        }
        long skipped = suppressed.getAndSet(0);
        if (skipped > 0) {
            log.error("{}（前 {}ms 内另有 {} 条相同故障被抑制）", message, windowMs, skipped, t);
        } else {
            log.error(message, t);
        }
    }

    /**
     * 记一条<b>告警</b>；窗口内的重复只累计计数不落盘。
     *
     * <p>与 {@link #error} 共用同一个窗口与计数器 —— 一个实例只服务一类故障，
     * 混用两个级别会让抑制计数的含义变得不可解释。
     *
     * <p><b>典型用途是「确定性拒绝」这类高频且可预期的事件</b>：
     * 每条被拒消息的完整信息已经在 DLQ 记录里（payload + reason header），
     * 并已按 reason 计入 {@code iot_ingress_dlq_total}，日志逐条再打一遍是第三份拷贝 ——
     * 却是唯一能把磁盘写满的那份（2026-08-09 实测：驱动参数配错导致的拒绝风暴中，
     * 4 分钟 36MB / 15.9 万行，其中 158,547 行是这类 WARN）。
     */
    public void warn(String message) {
        long now = System.currentTimeMillis();
        long next = nextLogAt.get();
        if (now < next) {
            suppressed.incrementAndGet();
            return;
        }
        if (!nextLogAt.compareAndSet(next, now + windowMs)) {
            suppressed.incrementAndGet();
            return;
        }
        long skipped = suppressed.getAndSet(0);
        if (skipped > 0) {
            log.warn("{}（前 {}ms 内另有 {} 条同类被抑制）", message, windowMs, skipped);
        } else {
            log.warn(message);
        }
    }

    /**
     * 故障恢复后调用：让下一次故障的首条错误立即落盘，而不是等窗口过期。
     */
    public void reset() {
        nextLogAt.set(0);
        long skipped = suppressed.getAndSet(0);
        if (skipped > 0) {
            log.info("故障已恢复，期间共抑制 {} 条重复错误日志", skipped);
        }
    }
}

package com.unisence.iot.engine.verticle;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * verticle 停机时把自有线程「<b>等停</b>」而不是「叫停」。
 *
 * <p>存在的理由是一条 Vert.x 生命周期事实：{@code vertx.close()} 先 undeploy 全部 verticle，
 * <b>再</b>关闭它自己创建的资源 —— 包括 {@code Redis.createClient(vertx, ...)} 与
 * {@code Pool.pool(vertx, ...)} 底下的 {@code NetClient}。因此 {@code stop()} 一返回，
 * 传输层随时可能被拆掉。若 {@code stop()} 只是 {@code interrupt()} / {@code shutdownNow()}
 * 就返回，在途任务会正好撞上被拆掉的连接：本仓实测每次停机固定刷一批
 * {@code RedisCommandException: ... CONNECTION_CLOSED}，看起来像 Redis 故障，
 * 实际是自己没等线程停完（vertx-pool-thread-affinity.md §3.4bis）。
 *
 * <p><b>先不打断</b>是刻意的：调用方在此之前已经把 {@code running} 置为 false，在途任务跑完
 * 当前这一批就会自行结束（最坏等一次 {@code XREADGROUP BLOCK} 或一次 poll 超时）。
 * 中断只会把「正常收尾并 ACK」变成「半途失败、留给别的实例重做」—— 换来的那点时间毫无价值。
 * 只有等超时了才升级为中断。
 */
@Slf4j
public final class VerticleQuiesce {

    /**
     * 停机静默期上限：verticle 等自有线程跑完在途工作的最长时间。
     *
     * <p>初值 10s，须同时满足：<b>大于</b>一轮在途工作的最坏耗时（transition 的
     * {@code XREADGROUP BLOCK} 是 1s，Kafka poll 一轮是 {@code fetch-max-wait-ms} + 处理），
     * 且<b>小于</b> {@code app.engine.shutdown-timeout-ms} —— 后者是整个
     * {@code vertx.close()} 的预算，本值把它花光了外层就只能超时放弃。
     * 定值见 {@code harness/delivery-backlog.md} R10.1。
     */
    public static final long STOP_TIMEOUT_MS = 10_000;

    /**
     * 升级为中断之后再给的宽限：中断能唤醒的线程立刻就醒，醒不来的再等也没用。
     */
    private static final long INTERRUPT_GRACE_MS = STOP_TIMEOUT_MS / 10;

    private VerticleQuiesce() {
    }

    /**
     * 等一个线程池把在途任务做完；超时才中断。调用前必须已置位停止标志，否则会一直有新任务进来。
     */
    public static void shutdown(String what, ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        if (awaitTermination(what, executor, STOP_TIMEOUT_MS)) {
            return;
        }
        log.warn("{}未在 {}ms 内自行结束，升级为中断", what, STOP_TIMEOUT_MS);
        executor.shutdownNow();
        if (!awaitTermination(what, executor, INTERRUPT_GRACE_MS)) {
            log.error("{}中断后仍未结束：接下来 Vert.x 会拆掉 Redis/MySQL 传输层，"
                      + "它的在途请求将报 CONNECTION_CLOSED", what);
        }
    }

    /**
     * 等一个长循环线程退出；超时才中断。语义与 {@link #shutdown} 一致。
     */
    public static void join(String what, Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(STOP_TIMEOUT_MS);
            if (thread.isAlive()) {
                log.warn("{}未在 {}ms 内自行退出，升级为中断", what, STOP_TIMEOUT_MS);
                thread.interrupt();
                thread.join(INTERRUPT_GRACE_MS);
            }
            if (thread.isAlive()) {
                log.error("{}中断后仍未退出：接下来 Vert.x 会拆掉 Redis/MySQL 传输层，"
                          + "它的在途请求将报 CONNECTION_CLOSED", what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待{}退出被中断，停机继续", what, e);
        }
    }

    private static boolean awaitTermination(String what, ExecutorService executor, long timeoutMs) {
        try {
            return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待{}结束被中断，停机继续", what, e);
            return false;
        }
    }
}

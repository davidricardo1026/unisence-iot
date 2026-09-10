package com.unisence.iot.engine.online;

import com.unisence.iot.common.batch.BatchResult;
import com.unisence.iot.common.batch.BatchWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 有界队列 + 单消费线程的攒批累积器（batch-write-strategy.md §五）。
 *
 * <p><b>仅供可重建载荷使用。</b>内存缓冲 + 有界容量 + 有界停机预算 ⇒ 持续过载下必然丢数据，
 * 这是数学事实，调参数改不掉。不可重建的载荷（append-only 历史、审计、计费流水）
 * 必须由持久缓冲承载，且确认点晚于写成功。
 *
 * <p>之所以不放进 {@code cn-common-core}：它只有一个调用方（续租），而且是个会自启线程、
 * 持有内存缓冲的组件 —— 摆进被 admin 与 engine 共享的零依赖内核，等于邀请别人拿它去缓冲
 * 不可丢的数据。等出现第二个同样可丢的调用方再考虑上移。
 *
 * <h2>为什么是消费循环而不是 ScheduledExecutorService</h2>
 * 攒批的本质是生产者/消费者，不是定时任务。用一条循环实现时，<b>串行是结构性的</b> ——
 * 一条循环、一个线程，{@link BatchWriter} 不可能重入。因此不需要「已排期」标志，
 * 也就不存在双重调度、漏唤醒、停机期并发刷新这一整类问题。定时则来自 {@code poll(timeout)}。
 *
 * <h2>批次边界（同 Kafka Producer 的 linger.ms / batch.size）</h2>
 * 队列空时循环阻塞，零 CPU。首条事件到达后开 {@code lingerMs} 窗口继续攒，
 * <b>攒满 {@code batchSize} 或窗口到期，先到者刷</b>。
 *
 * <h2>容量：只提供有界，不替调用方做丢弃决定</h2>
 * 入队原语是 {@link #tryOffer}（满即返回 false）与 {@link #offer}（满则阻塞，形成背压），
 * <b>两者都不丢弃任何数据</b>。要「丢最旧腾位」的调用方自己组合
 * {@link #evictOldest} + {@link #tryOffer}。满时的正确处置取决于载荷语义，本类看不到。
 */
public final class BatchAccumulator<V> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchAccumulator.class);

    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 10_000;
    /**
     * 队列空转时的醒来间隔，用于观察停机标志。只影响停机响应速度。
     */
    private static final long IDLE_POLL_MS = 200;

    private final BlockingQueue<V> queue;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final CountDownLatch terminated = new CountDownLatch(1);

    private final String name;
    private final Config config;
    private final BatchWriter<V> writer;
    /**
     * 毒药与超限批次的去向；由调用方决定丢弃、转 DLQ 还是仅计数告警。
     */
    private final Consumer<List<V>> onPoison;
    private final Thread worker;

    private volatile long drainDeadlineMs;

    /**
     * @param lingerMs     首条事件到达后的攒批窗口上限
     * @param batchSize    单批最大条数
     * @param capacity     队列容量上限；达到上限后 {@link #tryOffer} 返回 false，本类不自行丢弃
     * @param retryDelayMs {@code retryable} 项的重投退避
     * @param maxRetries   同一批的重试次数上限，超限转交 {@code onPoison}
     */
    public record Config(long lingerMs, int batchSize, int capacity, long retryDelayMs, int maxRetries) {

        public Config {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize 必须为正数: " + batchSize);
            }
            if (capacity < batchSize) {
                throw new IllegalArgumentException(
                    "capacity(" + capacity + ") 不得小于 batchSize(" + batchSize + ")：否则一批还没攒够就开始丢");
            }
            if (lingerMs < 0 || retryDelayMs < 0) {
                throw new IllegalArgumentException("lingerMs 与 retryDelayMs 不能为负");
            }
            if (maxRetries <= 0) {
                // 无限重试会让一个永久失败的批次堵死整条链路：重试期间不取新数据
                throw new IllegalArgumentException("maxRetries 必须为正数，禁止无限重试: " + maxRetries);
            }
        }
    }

    public BatchAccumulator(String name, Config config, BatchWriter<V> writer,
                            Consumer<List<V>> onPoison, ThreadFactory threadFactory) {
        this.name = name;
        this.config = config;
        this.writer = writer;
        this.onPoison = onPoison;
        // LinkedBlockingQueue 而非 ArrayBlockingQueue：前者 put/take 用两把独立的锁，
        // 生产者与消费者不互相阻塞；后者单锁，高频单条入队时竞争明显
        this.queue = new LinkedBlockingQueue<>(config.capacity());
        this.worker = threadFactory.newThread(this::consumeLoop);
    }

    /**
     * 显式启动消费线程。
     *
     * <p><b>刻意不在构造器里启动</b>：那会让 {@code this} 在构造完成前逃逸给另一条线程，
     * 任何字段赋值顺序的改动都可能静默破坏它；而且会导致「构造一个实例」必然带副作用，无法单独测试。
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            worker.start();
        }
    }

    /**
     * 默认：非守护平台线程。漏调 {@link #close()} 时宁可吊住 JVM，也好过静默丢事件。
     */
    public static ThreadFactory platformThreadFactory(String name) {
        return r -> Thread.ofPlatform().name("batch-accumulator-" + name).daemon(false).unstarted(r);
    }

    /**
     * 虚拟线程。仅当 {@link BatchWriter} 内不含 {@code synchronized} 包裹的阻塞 I/O 时才可用
     * （JDK 24 之前那会钉住全进程共享的载体线程），且需自行保证退出前调用 {@link #close()}
     * —— 虚拟线程恒为守护线程。
     */
    public static ThreadFactory virtualThreadFactory(String name) {
        return r -> Thread.ofVirtual().name("batch-accumulator-" + name).unstarted(r);
    }

    // ────────────────────────────── 生产者侧 ──────────────────────────────

    /**
     * 尝试入队，队列满立即返回 {@code false}。<b>不丢弃任何数据</b>。
     */
    public boolean tryOffer(V value) {
        if (rejectIfShutdown()) {
            return false;
        }
        return queue.offer(value);
    }

    /**
     * 入队；队列满则最多等待 {@code timeoutMs}，<b>对上游形成背压</b>。
     *
     * <p>它阻塞的是生产者线程 —— 若生产者是消费循环，这等价于暂停消费，这正是想要的效果。
     */
    public boolean offer(V value, long timeoutMs) throws InterruptedException {
        if (rejectIfShutdown()) {
            return false;
        }
        return queue.offer(value, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 移除并返回最旧一条，供调用方实现「丢最旧腾位」；队列空返回 {@code null}。
     */
    public V evictOldest() {
        return queue.poll();
    }

    /**
     * 当前积压条数；{@code LinkedBlockingQueue.size()} 是 O(1) 计数器，可放热路径。
     */
    public int pendingCount() {
        return queue.size();
    }

    public int capacity() {
        return config.capacity();
    }

    private boolean rejectIfShutdown() {
        if (shutdown.get()) {
            log.warn("[{}] 已进入停机流程，拒绝新事件", name);
            return true;
        }
        return false;
    }

    // ────────────────────────────── 消费循环 ──────────────────────────────

    private void consumeLoop() {
        List<V> retry = new ArrayList<>();
        int attempts = 0;
        try {
            while (true) {
                if (!retry.isEmpty()) {
                    attempts++;
                    retry = new ArrayList<>(attempt(retry).retryable());
                    if (retry.isEmpty()) {
                        attempts = 0;
                        continue;
                    }
                    if (attempts >= config.maxRetries()) {
                        // 重试封顶：超限批次必须让路。重试期间不取新数据，
                        // 一个永久失败的批次会把整条链路堵死
                        log.error("[{}] 批次重试 {} 次仍失败，转交毒药处置: size={}", name, attempts, retry.size());
                        handlePoison(retry);
                        retry = new ArrayList<>();
                        attempts = 0;
                        continue;
                    }
                    if (!backoff()) {
                        return;
                    }
                    continue;
                }

                List<V> batch = nextBatch();
                if (batch.isEmpty()) {
                    if (shutdown.get()) {
                        return;
                    }
                    continue;
                }
                retry = new ArrayList<>(attempt(batch).retryable());
                attempts = retry.isEmpty() ? 0 : 1;
                if (!retry.isEmpty() && !backoff()) {
                    return;
                }
            }
        } catch (Exception e) {
            log.error("[{}] 消费循环异常退出，剩余 {} 条不会再落地", name, queue.size() + retry.size(), e);
        } finally {
            int lost = queue.size() + retry.size();
            if (lost > 0) {
                // 停机丢数据是本类唯一无法靠重试兜住的场景，必须显式暴露
                log.error("[{}] 停止时仍有 {} 条未落地，已丢弃", name, lost);
            }
            terminated.countDown();
        }
    }

    /**
     * 取下一批：攒够 {@code batchSize} 或等满 {@code lingerMs}，先到者胜。
     *
     * <p>第一条用 {@code poll(IDLE_POLL_MS)} 而不是 {@code take()}：后者在队列空时永久阻塞，
     * 停机只能靠 {@code interrupt()} 打断 —— 而中断可能落在写入中途。定期醒来检查标志，
     * 换来「停机永不打断在途写入」。
     *
     * <p>每次拿到元素后跟一次 {@code drainTo}：突发流量一次锁获取取走一串，而不是每条各走一次 poll。
     */
    private List<V> nextBatch() throws InterruptedException {
        List<V> batch = new ArrayList<>(config.batchSize());
        V first = queue.poll(shutdown.get() ? 20 : IDLE_POLL_MS, TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        queue.drainTo(batch, config.batchSize() - batch.size());
        if (shutdown.get()) {
            // 排空时不再等 linger：目标是尽快清空，不是攒大批
            return batch;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.lingerMs());
        while (batch.size() < config.batchSize()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            V next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
            queue.drainTo(batch, config.batchSize() - batch.size());
        }
        return batch;
    }

    /**
     * 调用 {@link BatchWriter} 并把毒药项就地分流；返回值只用于取 retryable。
     */
    private BatchResult<V> attempt(List<V> batch) {
        BatchResult<V> result;
        try {
            result = writer.write(batch);
        } catch (Exception e) {
            // BatchWriter 契约要求不抛业务异常；真抛了只能保守当作可重试
            log.error("[{}] BatchWriter 违反契约抛出异常，保守按可重试处理: size={}", name, batch.size(), e);
            return BatchResult.allRetryable(batch);
        }
        if (result.hasPoison()) {
            handlePoison(result.poison());
        }
        return result;
    }

    private void handlePoison(List<V> poison) {
        try {
            onPoison.accept(poison);
        } catch (Exception e) {
            log.error("[{}] 毒药处置回调抛出异常，{} 条已丢弃", name, poison.size(), e);
        }
    }

    /**
     * @return {@code false} 表示应终止循环（被中断，或停机预算已耗尽）
     */
    private boolean backoff() {
        if (shutdown.get() && System.currentTimeMillis() >= drainDeadlineMs) {
            return false;
        }
        try {
            Thread.sleep(config.retryDelayMs());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ────────────────────────────── 停机 ──────────────────────────────

    @Override
    public void close() {
        shutdown(DEFAULT_DRAIN_TIMEOUT_MS);
    }

    /**
     * 优雅停机：停止收新事件，等消费循环排空后退出。
     *
     * <p><b>不中断消费线程</b> —— 中断可能落在写入中途。循环靠定期醒来观察标志，
     * 看到队列已空即自行退出。预算耗尽仍有残留时打 error 并给出条数。
     */
    public void shutdown(long drainTimeoutMs) {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        if (!started.get()) {
            terminated.countDown();
            return;
        }
        this.drainDeadlineMs = System.currentTimeMillis() + drainTimeoutMs;
        log.info("[{}] 开始优雅停机: 待落地={} 排空预算={}ms", name, queue.size(), drainTimeoutMs);
        try {
            long budget = drainTimeoutMs + config.retryDelayMs() + IDLE_POLL_MS;
            if (!terminated.await(budget, TimeUnit.MILLISECONDS)) {
                log.error("[{}] 排空超时，强制中断消费线程: 待落地={}", name, queue.size());
                worker.interrupt();
                terminated.await(IDLE_POLL_MS * 5, TimeUnit.MILLISECONDS);
            } else {
                log.info("[{}] 优雅停机完成", name);
            }
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
        }
    }
}

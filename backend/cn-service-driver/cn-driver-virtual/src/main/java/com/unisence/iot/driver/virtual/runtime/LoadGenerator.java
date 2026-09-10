package com.unisence.iot.driver.virtual.runtime;

import com.unisence.iot.driver.common.kafka.DeviceDataPublisher;
import com.unisence.iot.driver.virtual.config.VirtualDriverProperties;
import com.unisence.iot.message.*;
import com.unisence.iot.message.codec.MessagePackMessageCodec;
import com.unisence.iot.message.type.HeartbeatStatus;
import com.unisence.iot.message.type.NodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

/**
 * 压测负载生成器（driver-runtime-design.md §十）。
 *
 * <p>与第一版「每设备一个 {@code scheduleWithFixedDelay}」的联调形态完全不同：
 * 设备不再逐条枚举而是按序号生成，发送不再按设备排程而是**固定线程数 + 定速取模选设备**。
 * 10 万设备在这里只是一个 int，不产生任何 per-device 对象或任务。
 *
 * <p>四条实现红线见 §10.4：定速不补发、背压走 {@code Semaphore}、预热样本不进分位、
 * 同设备同数据类型的业务时间严格递增。
 */
public final class LoadGenerator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    /**
     * 定速线程落后超过本值时放弃补发、直接重新对齐。
     *
     * <p>补发会在下游恢复的瞬间打出一个**并不存在的峰值**，把 rebalance/restore 场次的
     * 观测彻底污染。宁可如实记录「这段时间没发够」，也不要伪造一个尖峰。
     */
    private static final long REALIGN_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(1);

    /**
     * 发送耗时采样池容量。定长环形写入，报告时快照排序取分位 —— 避免引入 HdrHistogram 依赖，
     * 也避免无界保留每条样本。
     */
    private static final int LATENCY_SAMPLES = 16384;

    private final VirtualDriverProperties properties;
    private final VirtualDriverProperties.Load load;
    private final DeviceDataPublisher publisher;
    /**
     * 解析一次后固定：热路径上不重复拼字符串。
     */
    private final List<String> identifiers;
    private final List<String> eventIdentifiers;
    private final double eventRatio;
    /**
     * 事件标识符 → 该事件在物模型里声明的参数标识符列表。
     * 配置格式 {@code event1:p1|p2,event2:p3}，为空则该事件不带参数。
     */
    private final Map<String, List<String>> eventParams;
    private final double deviceHeartbeatRatio;
    /** 本压测进程的实例身份，服务心跳用；进程内固定。 */
    private final String instanceId = UUID.randomUUID().toString();
    private final long startedAtMs = System.currentTimeMillis();
    /**
     * 压测进程内固定发送纪元；属性/事件各自维护设备维度 sequence。
     */
    private final long deliveryEpoch = startedAtMs;
    private final AtomicLongArray propertySequences;
    private final AtomicLongArray eventSequences;
    /** 属性/事件分别维护设备维度的严格递增业务时间，避免毫秒主键碰撞。 */
    private final AtomicLongArray propertyOccurredAtMs;
    private final AtomicLongArray eventOccurredAtMs;
    private final long serviceHeartbeatIntervalMs;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong realigned = new AtomicLong();
    private final AtomicLong maxLagNanos = new AtomicLong();
    private final AtomicLongArray latencyNanos = new AtomicLongArray(LATENCY_SAMPLES);
    private final AtomicLong latencyCursor = new AtomicLong();

    /**
     * 预热结束前不采集分位样本：首发含元数据拉取与连接建立，混进 p99 会得到与稳态无关的数字。
     */
    private volatile boolean sampling;
    private volatile long measureStartNanos;
    private volatile long measureStartSent;

    private Semaphore inFlight;
    private Thread[] publishers;
    private Thread reporter;
    private Thread controller;
    private final CountDownLatch finished = new CountDownLatch(1);

    public LoadGenerator(VirtualDriverProperties properties, DeviceDataPublisher publisher) {
        this.properties = properties;
        this.load = properties.getLoad();
        this.publisher = publisher;
        this.identifiers = resolveIdentifiers(properties.getLoad());
        this.eventIdentifiers = List.copyOf(properties.getLoad().getEventIdentifiers());
        // 没配事件标识符就等于没开事件负载 —— 宁可静默退化为纯属性，
        // 也不要用一个物模型里不存在的标识符去测 DLQ 路径
        this.eventRatio = eventIdentifiers.isEmpty() ? 0d : properties.getLoad().getEventRatio();
        this.eventParams = parseEventParams(properties.getLoad().getEventParams());
        this.deviceHeartbeatRatio = properties.getLoad().getDeviceHeartbeatRatio();
        this.serviceHeartbeatIntervalMs = properties.getLoad().getServiceHeartbeatInterval().toMillis();
        this.propertySequences = new AtomicLongArray(load.getDeviceCount());
        this.eventSequences = new AtomicLongArray(load.getDeviceCount());
        this.propertyOccurredAtMs = new AtomicLongArray(load.getDeviceCount());
        this.eventOccurredAtMs = new AtomicLongArray(load.getDeviceCount());
    }

    /**
     * 显式配置的标识符优先；未配置时退回 {@code prop0..propN-1}
     * （只适用于为压测新建、字段自行约定的产品）。
     */
    private static List<String> resolveIdentifiers(VirtualDriverProperties.Load load) {
        if (!load.getPropertyIdentifiers().isEmpty()) {
            return List.copyOf(load.getPropertyIdentifiers());
        }
        List<String> generated = new ArrayList<>(load.getPropertyCount());
        for (int i = 0; i < load.getPropertyCount(); i++) {
            generated.add("prop" + i);
        }
        return List.copyOf(generated);
    }

    /**
     * 建档 → settle → 定速压属性。非阻塞：内部自建线程，调用方立即返回。
     */
    public void start() {
        validate();
        if (!running.compareAndSet(false, true)) {
            return;
        }
        inFlight = new Semaphore(load.getMaxInFlight());
        controller = new Thread(this::run, "load-controller");
        controller.setDaemon(false);
        controller.start();
    }

    private void run() {
        try {
            if (load.getProvision().isEnabled()) {
                provision();
                Duration settle = load.getProvision().getSettleWait();
                log.info("[压测] 建档发送完毕，等待 {} 让 engine 落库与元数据收敛", settle);
                if (!sleepInterruptibly(settle)) {
                    return;
                }
            } else {
                log.warn("[压测] 已跳过建档（provision.enabled=false）。"
                             + "未建档设备的属性会被 engine 丢弃、被 rule-stream 投 DLQ，"
                             + "此时测的是丢弃路径而非规则链路");
            }
            runPropertyPhase();
        } catch (RuntimeException error) {
            log.error("[压测] 负载生成异常终止: errorClass={}", error.getClass().getName(), error);
        } finally {
            finished.countDown();
        }
    }

    // ────────────────────────── 建档阶段 ──────────────────────────

    private void provision() {
        int total = load.getDeviceCount();
        long intervalNanos = 1_000_000_000L / Math.max(1, load.getProvision().getRps());
        log.info("[压测] 开始批量建档: deviceCount={} rps={}", total, load.getProvision().getRps());
        long next = System.nanoTime();
        long startMillis = System.currentTimeMillis();
        for (int i = 0; i < total && running.get(); i++) {
            publish(newCreateMessage(i));
            next += intervalNanos;
            parkUntil(next);
            if ((i + 1) % 10_000 == 0) {
                log.info("[压测] 建档进度: {}/{}", i + 1, total);
            }
        }
        awaitInFlightDrain();
        log.info("[压测] 建档完成: {} 台，耗时 {} ms，失败 {}",
                 total, System.currentTimeMillis() - startMillis, failed.get());
        // 建档阶段的计数不能混进属性阶段的统计，否则实际 rps 会被稀释
        sent.set(0);
        succeeded.set(0);
        failed.set(0);
    }

    // ────────────────────────── 属性阶段 ──────────────────────────

    private void runPropertyPhase() {
        int threads = load.getPublisherThreads();
        // 速率在发送线程间均分；每线程各自按绝对时间基准推进，不共享令牌桶（避免热点 CAS）
        long perThreadIntervalNanos = (long) threads * 1_000_000_000L / load.getTargetRps();
        measureStartNanos = System.nanoTime();
        measureStartSent = 0;

        log.info("[压测] 开始压属性: targetRps={} threads={} 每线程间隔={}µs "
                     + "devices={} 属性={} maxInFlight={} 分布={}",
                 load.getTargetRps(), threads, perThreadIntervalNanos / 1000,
                 load.getDeviceCount(), identifiers, load.getMaxInFlight(),
                 describeDistribution());

        publishers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> publishLoop(perThreadIntervalNanos), "load-publisher-" + i);
            thread.setDaemon(false);
            thread.start();
            publishers[i] = thread;
        }

        reporter = new Thread(this::reportLoop, "load-reporter");
        reporter.setDaemon(true);
        reporter.start();

        // 服务心跳单起一条守护线程按周期发：它表达「驱动实例还活着」，
        // 与设备数、消息量都无关，混进按 rps 限速的发送循环会让它的频率跟着负载走
        if (serviceHeartbeatIntervalMs > 0) {
            Thread svc = new Thread(this::serviceHeartbeatLoop, "load-service-heartbeat");
            svc.setDaemon(true);
            svc.start();
            log.info("[压测] 服务心跳已开启: 周期={}ms instanceId={}", serviceHeartbeatIntervalMs, instanceId);
        }

        startWarmupTimer();

        Duration duration = load.getDuration();
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            if (sleepInterruptibly(duration)) {
                log.info("[压测] 已达配置时长 {}，停止发送", duration);
            }
            running.set(false);
        }
        joinPublishers();
        awaitInFlightDrain();
        report("最终");
    }

    /**
     * 服务心跳循环。失败只记日志不计入压测失败计数 ——
     * 它不是被测吞吐的一部分，不该污染 rps 统计。
     */
    private void serviceHeartbeatLoop() {
        while (running.get()) {
            try {
                publish(newServiceHeartbeatMessage());
            } catch (RuntimeException error) {
                log.warn("[压测] 服务心跳发送失败: errorClass={}", error.getClass().getName(), error);
            }
            if (!sleepInterruptibly(Duration.ofMillis(serviceHeartbeatIntervalMs))) {
                return;
            }
        }
    }

    private void publishLoop(long intervalNanos) {
        long next = System.nanoTime();
        while (running.get()) {
            try {
                // 按 eventRatio 混入事件消息。默认 0 即全部为属性，与既有测法完全一致。
                // 混在同一循环而不是另起线程：要测的是「两类消息共享一条摄入链路」的真实形态
                publishTimed(pickMessage());
            } catch (RuntimeException error) {
                failed.incrementAndGet();
                log.error("[压测] 构造或发送上行消息失败: errorClass={}", error.getClass().getName(), error);
            }
            next += intervalNanos;
            long lag = System.nanoTime() - next;
            if (lag > REALIGN_THRESHOLD_NANOS) {
                // 发不动了：如实记录并重新对齐，不补发（§10.4）
                realigned.incrementAndGet();
                maxLagNanos.accumulateAndGet(lag, Math::max);
                next = System.nanoTime();
                continue;
            }
            if (lag > 0) {
                maxLagNanos.accumulateAndGet(lag, Math::max);
                continue;
            }
            parkUntil(next);
        }
    }

    /**
     * 选设备索引。
     *
     * <p>{@code hotDeviceCount > 0} 时按 {@code hotDeviceShare} 的概率落在热点段，
     * 其余均匀落在冷段 —— 场次 E「单设备占 30% 流量」即
     * {@code hot-device-count: 1} + {@code hot-device-share: 0.3}。
     */
    private int pickDeviceIndex() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int hot = load.getHotDeviceCount();
        if (hot > 0 && random.nextDouble() < load.getHotDeviceShare()) {
            return random.nextInt(hot);
        }
        int cold = load.getDeviceCount() - hot;
        return cold <= 0 ? random.nextInt(load.getDeviceCount()) : hot + random.nextInt(cold);
    }

    // ────────────────────────── 发送与背压 ──────────────────────────

    /**
     * 发送并采样耗时。
     *
     * <p>背压来自 {@code inFlight.acquire()}：Kafka 慢下来时发送线程会阻塞，
     * 实际 rps 随之下降 —— **这正是要观测的现象**，不得改成丢弃或无界排队。
     */
    private void publishTimed(IotMessage message) {
        long startNanos = System.nanoTime();
        publish(message);
        if (sampling) {
            long index = latencyCursor.getAndIncrement() & (LATENCY_SAMPLES - 1);
            latencyNanos.set((int) index, System.nanoTime() - startNanos);
        }
    }

    private void publish(IotMessage message) {
        try {
            inFlight.acquire();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            running.set(false);
            return;
        }
        sent.incrementAndGet();
        CompletableFuture<?> future;
        try {
            future = publisher.publish(message);
        } catch (RuntimeException error) {
            inFlight.release();
            failed.incrementAndGet();
            throw error;
        }
        future.whenComplete((result, error) -> {
            inFlight.release();
            if (error == null) {
                succeeded.incrementAndGet();
            } else {
                failed.incrementAndGet();
            }
        });
    }

    private void awaitInFlightDrain() {
        int permits = load.getMaxInFlight();
        try {
            if (!inFlight.tryAcquire(permits, 30, TimeUnit.SECONDS)) {
                log.warn("[压测] 等待发送排空超时: 仍有 {} 条未完成", permits - inFlight.availablePermits());
                return;
            }
            inFlight.release(permits);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("[压测] 等待发送排空被中断");
        }
    }

    // ────────────────────────── 指标 ──────────────────────────

    private void startWarmupTimer() {
        Duration warmup = load.getWarmup();
        if (warmup == null || warmup.isZero() || warmup.isNegative()) {
            beginSampling();
            return;
        }
        Thread timer = new Thread(() -> {
            if (sleepInterruptibly(warmup)) {
                beginSampling();
            }
        }, "load-warmup");
        timer.setDaemon(true);
        timer.start();
        log.info("[压测] 预热 {} —— 其间样本不计入分位统计", warmup);
    }

    private void beginSampling() {
        measureStartNanos = System.nanoTime();
        measureStartSent = sent.get();
        sampling = true;
        log.info("[压测] 预热结束，开始采集稳态指标");
    }

    private void reportLoop() {
        Duration interval = load.getReportInterval();
        while (running.get()) {
            if (!sleepInterruptibly(interval)) {
                return;
            }
            report("周期");
        }
    }

    private void report(String phase) {
        long elapsedNanos = Math.max(1, System.nanoTime() - measureStartNanos);
        long delta = sent.get() - measureStartSent;
        long actualRps = delta * 1_000_000_000L / elapsedNanos;
        long[] percentiles = latencySnapshot();
        log.info("[压测·{}] 已发={} 成功={} 失败={} | 实际rps={}（目标 {}）| 在途={} | "
                     + "发送耗时 p50={}µs p95={}µs p99={}µs max={}µs | 重对齐={} 次 最大滞后={}ms",
                 phase, sent.get(), succeeded.get(), failed.get(),
                 actualRps, load.getTargetRps(),
                 load.getMaxInFlight() - inFlight.availablePermits(),
                 percentiles[0] / 1000, percentiles[1] / 1000,
                 percentiles[2] / 1000, percentiles[3] / 1000,
                 realigned.get(), maxLagNanos.get() / 1_000_000);
    }

    /**
     * @return {@code [p50, p95, p99, max]}，纳秒；样本不足时全为 0
     */
    private long[] latencySnapshot() {
        int size = (int) Math.min(latencyCursor.get(), LATENCY_SAMPLES);
        if (size == 0) {
            return new long[]{0, 0, 0, 0};
        }
        long[] copy = new long[size];
        for (int i = 0; i < size; i++) {
            copy[i] = latencyNanos.get(i);
        }
        Arrays.sort(copy);
        return new long[]{
            copy[(int) (size * 0.50)],
            copy[Math.min(size - 1, (int) (size * 0.95))],
            copy[Math.min(size - 1, (int) (size * 0.99))],
            copy[size - 1]};
    }

    private String describeDistribution() {
        return load.getHotDeviceCount() <= 0
            ? "均匀"
            : "倾斜（" + load.getHotDeviceCount() + " 台热点占 "
            + Math.round(load.getHotDeviceShare() * 100) + "% 流量）";
    }

    // ────────────────────────── 消息构造 ──────────────────────────

    private String deviceCode(int index) {
        return load.getDeviceCodePrefix() + String.format(Locale.ROOT, "%06d", index);
    }

    private DeviceCreateMessage newCreateMessage(int index) {
        return new DeviceCreateMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, UUID.randomUUID().toString(),
            System.currentTimeMillis(), properties.getSource(),
            load.getProductKey(), deviceCode(index), "压测设备" + index,
            NodeType.DIRECT, null, new LinkedHashMap<>(load.getFormData()));
    }

    /**
     * 按配置比例在属性 / 事件 / 设备心跳之间取一条。
     *
     * <p>三类混在**同一个发送循环**而不是各起一条线程：要测的是
     * 「多类消息共享一条摄入链路」的真实形态 —— 事件与心跳都落在 `iot.event`，
     * 与属性的 `iot.raw.data` 是两个消费组，但共享设备准入、续租与元数据缓存。
     */
    private IotMessage pickMessage() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double dice = random.nextDouble();
        if (dice < eventRatio) {
            return newEventMessage(pickDeviceIndex());
        }
        if (dice < eventRatio + deviceHeartbeatRatio) {
            return newDeviceHeartbeatMessage(pickDeviceIndex());
        }
        return newPropertyMessage(pickDeviceIndex());
    }

    /**
     * 设备心跳：<b>没有业务数据时的保底活性信号</b>，engine 侧唯一职责是续租。
     * {@code status} / {@code metrics} 由驱动代发，engine 本轮不消费其取值。
     */
    private DeviceHeartbeatMessage newDeviceHeartbeatMessage(int index) {
        return new DeviceHeartbeatMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, UUID.randomUUID().toString(),
            System.currentTimeMillis(), properties.getSource(),
            load.getProductKey(), deviceCode(index), HeartbeatStatus.ONLINE, Map.of());
    }

    /**
     * 服务心跳：驱动实例租约，engine 用它区分「设备掉线」与「驱动掉线」。
     * 与设备数、消息量都无关，一个实例一个周期一条。
     */
    private ServiceHeartbeatMessage newServiceHeartbeatMessage() {
        return new ServiceHeartbeatMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, UUID.randomUUID().toString(),
            System.currentTimeMillis(), properties.getSource(),
            properties.getSource(), instanceId, startedAtMs, "loadtest", null, Map.of());
    }

    /**
     * 事件消息。
     *
     * <p><b>params 的键必须是该事件在物模型里声明的参数标识符</b>，值类型也必须匹配 ——
     * 否则 {@code EventValidator} 会以 {@code THING_MODEL_IDENTIFIER_UNKNOWN} 整条拒绝，
     * 压测到的就变成 DLQ 路径而不是正常事件链路（2026-08-09 实测：
     * 用 {@code code}/{@code detail} 造参数导致 12.3 万条进 DLQ）。
     *
     * <p>因此参数由 {@link #eventParams} 按事件标识符提供，来源是调用方配置，
     * 而不是在此硬编码 —— 换产品换物模型时不必改代码。
     */
    private DeviceEventMessage newEventMessage(int index) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String identifier = eventIdentifiers.get(random.nextInt(eventIdentifiers.size()));
        Map<String, Object> params = new LinkedHashMap<>();
        for (String name : eventParams.getOrDefault(identifier, List.of())) {
            params.put(name, "v" + random.nextInt(1000));
        }
        return new DeviceEventMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, UUID.randomUUID().toString(),
            nextOccurredAt(eventOccurredAtMs, index), properties.getSource(),
            deliveryEpoch, eventSequences.getAndIncrement(index),
            load.getProductKey(), deviceCode(index), identifier, params);
    }

    private DevicePropertyMessage newPropertyMessage(int index) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double factor = Math.pow(10, load.getPropertyScale());
        Map<String, Object> values = new LinkedHashMap<>();
        for (String identifier : identifiers) {
            double raw = random.nextDouble(load.getPropertyMin(), load.getPropertyMax());
            values.put(identifier, Math.round(raw * factor) / factor);
        }
        return new DevicePropertyMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, UUID.randomUUID().toString(),
            nextOccurredAt(propertyOccurredAtMs, index), properties.getSource(),
            deliveryEpoch, propertySequences.getAndIncrement(index),
            load.getProductKey(), deviceCode(index), values);
    }

    /**
     * 同设备同数据类型的 occurredAt 严格递增；均匀负载下通常一次 CAS 即成功，热点设备并发时仍不碰撞。
     */
    private static long nextOccurredAt(AtomicLongArray clocks, int index) {
        long wallClock = System.currentTimeMillis();
        while (true) {
            long previous = clocks.get(index);
            long next = previous >= wallClock ? previous + 1L : wallClock;
            if (clocks.compareAndSet(index, previous, next)) {
                return next;
            }
        }
    }

    /**
     * 解析 {@code event1:p1|p2,event2:p3} 形式的事件参数声明。
     */
    private static Map<String, List<String>> parseEventParams(String spec) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return result;
        }
        for (String entry : spec.split(",")) {
            String[] kv = entry.split(":", 2);
            if (kv.length != 2 || kv[0].isBlank()) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String n : kv[1].split("\\|")) {
                if (!n.isBlank()) {
                    names.add(n.trim());
                }
            }
            result.put(kv[0].trim(), List.copyOf(names));
        }
        return result;
    }

    // ────────────────────────── 辅助 ──────────────────────────

    private static void parkUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    /**
     * @return {@code false} 表示被中断或已停止，调用方应立即退出
     */
    private boolean sleepInterruptibly(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return running.get();
        }
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
            return running.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.debug("[压测] 等待被中断，准备退出");
            return false;
        }
    }

    private void joinPublishers() {
        if (publishers == null) {
            return;
        }
        for (Thread thread : publishers) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                log.warn("[压测] 等待发送线程结束被中断: thread={}", thread.getName());
                return;
            }
        }
    }

    // ────────────────────────── 配置校验 ──────────────────────────

    private void validate() {
        requireText(load.getProductKey(), "app.driver.virtual.load.product-key");
        requirePositive(load.getDeviceCount(), "app.driver.virtual.load.device-count");
        requirePositive(load.getPropertyCount(), "app.driver.virtual.load.property-count");
        requirePositive(load.getTargetRps(), "app.driver.virtual.load.target-rps");
        requirePositive(load.getPublisherThreads(), "app.driver.virtual.load.publisher-threads");
        requirePositive(load.getMaxInFlight(), "app.driver.virtual.load.max-in-flight");
        if (load.getPropertyMin() > load.getPropertyMax()) {
            throw new IllegalArgumentException("app.driver.virtual.load.property-min 不得大于 property-max");
        }
        if (load.getPropertyScale() < 0 || load.getPropertyScale() > 6) {
            throw new IllegalArgumentException("app.driver.virtual.load.property-scale 必须在 0..6");
        }
        if (load.getHotDeviceCount() < 0 || load.getHotDeviceCount() > load.getDeviceCount()) {
            throw new IllegalArgumentException(
                "app.driver.virtual.load.hot-device-count 必须在 0..device-count");
        }
        if (load.getHotDeviceShare() < 0d || load.getHotDeviceShare() > 1d) {
            throw new IllegalArgumentException("app.driver.virtual.load.hot-device-share 必须在 0..1");
        }
        if (load.getHotDeviceCount() > 0 && load.getHotDeviceShare() <= 0d) {
            throw new IllegalArgumentException(
                "配置了 hot-device-count 却没有 hot-device-share，倾斜不会生效");
        }
        // 预热吃满整个时长时，sampling 到收尾才打开，分位与实际 rps 全是 0 —— 报告等于没跑
        Duration duration = load.getDuration();
        Duration warmup = load.getWarmup();
        if (duration != null && !duration.isZero() && !duration.isNegative()
            && warmup != null && warmup.compareTo(duration) >= 0) {
            throw new IllegalArgumentException(
                "app.driver.virtual.load.warmup (" + warmup + ") 必须小于 duration (" + duration
                    + ")，否则稳态样本为空，分位统计与实际 rps 全为 0");
        }
        // 目标速率低于线程数时，每线程间隔会被整除成 0，退化为不受控的满速发送
        if (load.getTargetRps() < load.getPublisherThreads()) {
            throw new IllegalArgumentException(
                "app.driver.virtual.load.target-rps (" + load.getTargetRps()
                    + ") 不得小于 publisher-threads (" + load.getPublisherThreads() + ")");
        }
    }

    private static void requireText(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " 不能为空");
        }
    }

    private static void requirePositive(int value, String path) {
        if (value <= 0) {
            throw new IllegalArgumentException(path + " 必须大于 0，当前 " + value);
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("[压测] 收到停止信号");
        if (controller != null) {
            controller.interrupt();
        }
        try {
            if (!finished.await(30, TimeUnit.SECONDS)) {
                log.warn("[压测] 等待负载线程收尾超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("[压测] 等待负载线程收尾被中断");
        }
    }
}

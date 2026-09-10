package com.unisence.iot.engine.metadata;

import com.unisence.iot.metadata.*;
import io.vertx.core.AbstractVerticle;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 元数据总线的触发源（metadata-sync-bus.md §7.1、§0.4）。
 *
 * <p>四条发现路径按「快 → 慢、不可靠 → 可靠」分层，全部只调用
 * {@link MetadataSyncService#observeDesiredHead}：
 *
 * <pre>
 * Pub/Sub 正常                → 毫秒级
 * 订阅断线/消息丢失            → 5s Redis head 探测
 * Lua 未执行/Redis 整体不可用  → 30s MySQL 反熵
 * Redis 与 MySQL 同时不可用    → 保留 LKG，不推进 appliedHead
 * </pre>
 *
 * <p><b>MySQL 反熵是强制任务</b>：Redis 恢复后也不关闭、周期也不允许配成 0。
 * 它是唯一一条不依赖 Redis 的发现路径，关掉它就等于把正确性押在缓存上。
 */
@Slf4j
public final class MetadataSyncVerticle extends AbstractVerticle {

    private final MetadataSyncService syncService;
    private final MetadataControlRepository repository;
    private final MetadataHintStore hintStore;
    private final MetadataInstanceReporter reporter;
    private final DeviceMetadataCache deviceCache;
    private final MetadataProperties properties;
    private final VertxPoolSocketLedger socketLedger;
    private final boolean redisAvailable;

    /**
     * 订阅断开后置位，由 head 探测任务顺带重连 —— 不额外起一个重连定时器。
     */
    private final AtomicBoolean resubscribeNeeded = new AtomicBoolean();

    private long headProbeTimer;
    private long antiEntropyTimer;
    private long heartbeatTimer;

    public MetadataSyncVerticle(MetadataSyncService syncService,
                                MetadataControlRepository repository,
                                MetadataHintStore hintStore,
                                MetadataInstanceReporter reporter,
                                DeviceMetadataCache deviceCache,
                                MetadataProperties properties,
                                VertxPoolSocketLedger socketLedger,
                                boolean redisAvailable) {
        this.socketLedger = socketLedger;
        this.syncService = syncService;
        this.repository = repository;
        this.hintStore = hintStore;
        this.reporter = reporter;
        this.deviceCache = deviceCache;
        this.properties = properties;
        this.redisAvailable = redisAvailable;
    }

    @Override
    public void start() {
        if (redisAvailable) {
            startSubscription();
            headProbeTimer = vertx.setPeriodic(
                properties.jitter(properties.redisHeadProbeIntervalMs()),
                id -> guarded("Redis head 探测", this::probeHead));
            heartbeatTimer = vertx.setPeriodic(
                properties.instanceHeartbeatIntervalMs(), id -> guarded("实例心跳", this::heartbeat));
        } else {
            log.warn("Redis 不可用，元数据总线降级为仅 MySQL 反熵：变更发现延迟上升到 {}ms",
                     properties.dbReconcileIntervalMs());
        }
        antiEntropyTimer = vertx.setPeriodic(
            properties.jitter(properties.dbReconcileIntervalMs()), id -> guarded("MySQL 反熵", this::antiEntropy));
        log.info("元数据触发源已启动: pubsub={} headProbe={}ms antiEntropy={}ms",
                 redisAvailable, properties.redisHeadProbeIntervalMs(), properties.dbReconcileIntervalMs());
    }

    /**
     * 建立订阅并在确认可接收之后补读一次 head。
     *
     * <p>补读是必需的：订阅只能收到「此刻之后」发布的提示，订阅建立之前发生的提交
     * 不会重播（Pub/Sub 无历史）。不补读就要等下一次探测才发现。
     */
    private void startSubscription() {
        boolean subscribed = hintStore.subscribe(
            head -> syncService.observeDesiredHead(head, MetadataSyncTrigger.REDIS_PUBSUB),
            () -> resubscribeNeeded.set(true));
        if (!subscribed) {
            resubscribeNeeded.set(true);
            return;
        }
        long head = hintStore.probeHead();
        if (head >= 0) {
            syncService.observeDesiredHead(head, MetadataSyncTrigger.REDIS_RECONNECTED);
        }
    }

    private void probeHead() {
        if (resubscribeNeeded.compareAndSet(true, false)) {
            log.info("检测到订阅断开，正在重建元数据提示订阅");
            startSubscription();
            return;
        }
        long head = hintStore.probeHead();
        if (head >= 0) {
            syncService.observeDesiredHead(head, MetadataSyncTrigger.REDIS_HEAD_PROBE);
        }
    }

    /**
     * MySQL 反熵：直接读权威水位。
     *
     * <p>它同时兜住两类故障：Redis 整体不可用，以及「afterCommit 回调还没执行 admin 就崩了」
     * —— 后者数据已提交但提示从未发出，只有反熵能发现。
     */
    private void antiEntropy() {
        try {
            long committedHead = repository.readCommittedHead();
            syncService.observeDesiredHead(committedHead, MetadataSyncTrigger.MYSQL_ANTI_ENTROPY);
        } catch (Exception e) {
            // MySQL 短暂不可用：保留 LKG、不推进水位，下一轮继续
            log.error("MySQL 元数据反熵失败，保留 LKG 等待下一轮", e);
        }
        // 搭反熵的周期做连接账本比对，不再单开定时器与配置项。
        // 放在反熵之后而非之前：反熵失败往往正是连接耗尽的表现，此时这条比对最有诊断价值
        socketLedger.check();
    }

    private void heartbeat() {
        reporter.report(deviceCache);
    }

    /**
     * 定时回调<b>直接在本 verticle 的 context 上执行</b>，只加一层异常兜底。
     *
     * <p>本 verticle 以 {@link io.vertx.core.ThreadingModel#VIRTUAL_THREAD} 部署，其 context
     * 的执行器是 {@code WorkerExecutor(virtualThreadWorkerPool, WorkerTaskQueue)} —— 定时器回调
     * 本来就跑在虚拟线程上，阻塞调用合法；{@code Future.await()} 会让出 task queue，
     * 因此一次 MySQL 往返不会挡住心跳与探测。
     *
     * <p><b>禁止再包一层 {@code Thread.ofVirtual().start()}</b>。裸虚拟线程没有 Vert.x context，
     * {@code VertxImpl.getOrCreateContext()} 会为每次调用新建 context，其 event loop 由线程 ID
     * 取模决定；而 Vert.x SQL 连接池只复用 event loop 相同的连接，于是复用永远命不中、
     * 连接池被迫为每个 event loop 各建一条连接。详见
     * {@code metadata-sync-mysql-connection-exhaustion-incident.md} §三。
     */
    private void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("元数据触发任务失败: 任务={}", what, e);
        }
    }

    @Override
    public void stop() {
        cancel(headProbeTimer);
        cancel(antiEntropyTimer);
        cancel(heartbeatTimer);
        hintStore.close();
        if (redisAvailable) {
            reporter.deregister();
        }
        log.info("元数据触发源已停止");
    }

    private void cancel(long timerId) {
        if (timerId != 0) {
            vertx.cancelTimer(timerId);
        }
    }
}

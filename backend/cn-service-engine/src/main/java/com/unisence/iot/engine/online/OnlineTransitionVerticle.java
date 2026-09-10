package com.unisence.iot.engine.online;

import com.unisence.iot.common.batch.BatchResult;
import com.unisence.iot.engine.config.EngineOnlineProperties;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.repository.DeviceStatusUpdate;
import com.unisence.iot.engine.repository.EngineMySQLRepository;
import com.unisence.iot.engine.storage.OnlineLogWriter;
import com.unisence.iot.engine.verticle.ThrottledErrorLog;
import com.unisence.iot.engine.verticle.VerticleQuiesce;
import com.unisence.iot.metadata.*;
import io.vertx.core.AbstractVerticle;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * transition stream 的可靠落库（device-online-state-design.md §7.1、§10.5）。
 *
 * <p>它取代了原先的两个内存累积器。区别只有一句话，但决定了数据会不会丢：
 * <b>确认（{@code XACK}）发生在 MySQL 与时序库都写成功之后</b>。因此进程在任何一步宕机，
 * 未确认项都留在 PEL 里，由别的实例 {@code XAUTOCLAIM} 接管重做。
 *
 * <p>两个落点<b>必须由同一个 drainer 处理并共用一次 ACK</b>：拆成两个互不协调的累积器时，
 * 没有任何一方知道「另一边写成功没有」，于是根本无法判定何时可以确认。
 *
 * <p>消息在 ACK 前可能被重复处理，因此两个落点都必须幂等 ——
 * MySQL 靠 {@code (status_event_ms, status_event_seq)} fencing，时序库靠时间戳 + tag 覆盖写。
 */
@Slf4j
public final class OnlineTransitionVerticle extends AbstractVerticle {

    private final OnlineTransitionStore transitionStore;
    private final DeviceLeaseStore leaseStore;
    private final ShardOwnership ownership;
    private final MetadataSyncService metadataSyncService;
    private final DeviceMetadataCache deviceCache;
    private final UnknownDeviceRepairCoordinator unknownDeviceRepair;
    private final EngineMySQLRepository repository;
    private final OnlineLogWriter onlineLogWriter;
    private final EngineOnlineProperties props;
    private final String consumerId;
    /**
     * 可为 {@code null}：指标为可选装配。
     */
    private final EngineMetrics metrics;

    private final AtomicBoolean running = new AtomicBoolean(true);
    /**
     * 固定大小的 shard 排空线程池，容量即并发上限。
     *
     * <p><b>必须是长生命周期线程池，不能每轮新建线程。</b>{@link #drainGroup} 会经
     * {@code EngineMySQLRepository} 写 MySQL，而 Vert.x SQL 连接池的首选复用判据
     * {@code SAME_EVENT_LOOP_SELECTOR} 只复用 event loop 与调用方相同的连接；调用方的
     * event loop 由 {@code VertxImpl.stickyEventLoop()} 按线程 ID 取模决定。每轮新建线程
     * ⇒ 线程 ID 每轮都变 ⇒ 复用永不命中 ⇒ 连接池为每个 event loop 各建一条连接并反复重建
     * （metadata-sync-mysql-connection-exhaustion-incident.md §四bis）。
     * {@code Thread.ofPlatform()} 不豁免这条 —— 平台线程 ID 同样单调递增、永不复用。
     *
     * <p>仍用平台线程而非虚拟线程：本路径经 {@code OnlineLogWriter} 写 GreptimeDB，
     * 固定池封顶并发并保持 ACK 顺序（见 {@code engine-runtime-io.md} §2.1.1）。
     */
    private ExecutorService shardWorkers;
    private Thread driver;
    /**
     * 认领与积压巡检线程（契约 §10.5.2）。与 {@link #driver} 分开是因为二者的节奏依据不同：
     * 排空跟着新项走（多流 {@code BLOCK} 被 {@code XADD} 唤醒），
     * 巡检跟着 {@code transition-claim-idle-ms} 走。合在一起必然让其中一方被另一方的节奏绑架。
     */
    private Thread sweeper;
    /**
     * 停机信号，专供 {@link #sweepLoop} 的等待使用。
     *
     * <p><b>不能用 {@code Thread.sleep(sweepIntervalMs)}</b>：巡检周期是
     * {@code transition-claim-idle-ms / 2}（当前 15s），而 {@code VerticleQuiesce.STOP_TIMEOUT_MS}
     * 是 10s —— 停机时巡检线程多半正睡在中间，join 必然超时并打出「未自行退出，升级为中断」。
     * 那是一条<b>每次停机都出现的伪告警</b>，仓库规则明令禁止
     * （`vertx-pool-thread-affinity.md` §3.4ter）。用 latch 让 {@link #stop()} 能立即唤醒它。
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    /**
     * 排空失败的抑制式日志。窗口取 {@code transition-block-ms}：与退避周期一致，
     * 保证「退避一轮最多落一条」而不是每轮一条（H16）。
     */
    private ThrottledErrorLog failureLog;

    public OnlineTransitionVerticle(OnlineTransitionStore transitionStore,
                                    DeviceLeaseStore leaseStore,
                                    ShardOwnership ownership,
                                    MetadataSyncService metadataSyncService,
                                    DeviceMetadataCache deviceCache,
                                    UnknownDeviceRepairCoordinator unknownDeviceRepair,
                                    EngineMySQLRepository repository,
                                    OnlineLogWriter onlineLogWriter,
                                    EngineOnlineProperties props,
                                    String consumerId) {
        this(transitionStore, leaseStore, ownership, metadataSyncService, deviceCache,
             unknownDeviceRepair, repository, onlineLogWriter, props, consumerId, null);
    }

    public OnlineTransitionVerticle(OnlineTransitionStore transitionStore,
                                    DeviceLeaseStore leaseStore,
                                    ShardOwnership ownership,
                                    MetadataSyncService metadataSyncService,
                                    DeviceMetadataCache deviceCache,
                                    UnknownDeviceRepairCoordinator unknownDeviceRepair,
                                    EngineMySQLRepository repository,
                                    OnlineLogWriter onlineLogWriter,
                                    EngineOnlineProperties props,
                                    String consumerId,
                                    EngineMetrics metrics) {
        this.metrics = metrics;
        this.transitionStore = transitionStore;
        this.leaseStore = leaseStore;
        this.ownership = ownership;
        this.metadataSyncService = metadataSyncService;
        this.deviceCache = deviceCache;
        this.unknownDeviceRepair = unknownDeviceRepair;
        this.repository = repository;
        this.onlineLogWriter = onlineLogWriter;
        this.props = props;
        this.consumerId = consumerId;
    }

    @Override
    public void start() {
        failureLog = new ThrottledErrorLog(log, props.transitionBlockMs());
        transitionStore.ensureConsumerGroups();
        // 池容量即并发上限，取代原先的 Semaphore：两者语义等价，但池同时解决了线程 ID 稳定性。
        // 并发封顶发生在「提交任务之前」（提交满了就排队），符合 engine 热路径红线
        shardWorkers = Executors.newFixedThreadPool(
            props.transitionDrainShardConcurrency(), shardThreadFactory());
        // 长循环放独立线程，不占用 verticle context
        driver = Thread.ofPlatform().name("online-transition-driver").start(this::driveLoop);
        // 认领与积压巡检独立成环（契约 §10.5.2）：留在排空热循环里会让它的频率随热循环加快而上升
        sweeper = Thread.ofPlatform().name("online-transition-sweeper").start(this::sweepLoop);
        log.info("transition drainer 已启动: consumer={} shardConcurrency={} sweepIntervalMs={}",
                 consumerId, props.transitionDrainShardConcurrency(), sweepIntervalMs());
    }

    /**
     * 主循环：每轮重算归属，把自有 shard <b>按池容量切组</b>，每组一次多流 {@code XREADGROUP}。
     *
     * <p>归属每轮重算而不是启动时算一次：实例增减后必须尽快把无人认领的 shard 接过来。
     *
     * <p><b>分组数 = 池容量，是必须守住的不变量</b>（契约 §10.5.1）。退回「每 shard 一次阻塞读」
     * 会让空载一轮的耗时正比于 shard 数 —— 256 shard、池 8、每个空 shard 阻塞
     * {@code transition-block-ms}(1000) ⇒ 空载一轮 32 秒，而那就是跳变端到端延迟的下界。
     * 分组数跟着池容量走还能自适应：只分到 8 个 shard 时每组 1 个 stream，
     * 分到 256 个时每组 32 个，空载一轮恒等于一个 {@code BLOCK} 周期。
     */
    private void driveLoop() {
        while (running.get()) {
            boolean roundFailed = false;
            try {
                List<List<Integer>> groups = splitByConcurrency(ownership.ownedShards());
                List<Future<?>> workers = new ArrayList<>(groups.size());
                for (List<Integer> group : groups) {
                    if (!running.get()) {
                        break;
                    }
                    workers.add(shardWorkers.submit(() -> {
                        // 停机已发起：队列里还没开跑的组直接放弃本轮。
                        // 未 ACK 的项本就由别的实例 XAUTOCLAIM 接管，放弃是安全的
                        if (!running.get()) {
                            return;
                        }
                        try {
                            drainGroup(group);
                        } catch (Exception e) {
                            failureLog.error("排空 transition 分组失败: shards=" + group.size(), e);
                            throw e;
                        }
                    }));
                }
                // 仍然等齐本轮全部分组再进入下一轮：归属每轮重算的前提是上一轮已经收敛
                for (Future<?> worker : workers) {
                    try {
                        worker.get();
                    } catch (ExecutionException e) {
                        roundFailed = true;
                    }
                }
                if (groups.isEmpty()) {
                    // 本实例这一轮没分到 shard：短睡避免空转打满 CPU
                    Thread.sleep(props.transitionBlockMs());
                }
                if (!roundFailed) {
                    failureLog.reset();
                }
                if (roundFailed) {
                    // 【必须退避】正常时 XREADGROUP 的 BLOCK 隐式限速；下游一挂，readNew 立即失败返回，
                    // 本循环就变成全速空转。实测 Redis 停 45s 期间刷出 300 万行日志、文件涨到 6.7GB
                    // （hotpath-findings.md H16）—— 磁盘写满会把「一个依赖不可用」升级成「整机不可用」。
                    // 退避基数复用 transition-block-ms：它本就是本循环正常时的节奏
                    Thread.sleep(props.transitionBlockMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                failureLog.error("transition drainer 主循环异常，继续下一轮", e);
                sleepQuietly(props.transitionBlockMs());
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把自有 shard 平摊成<b>恰好池容量</b>个分组（不足则少于池容量，空组不产出）。
     *
     * <p>平摊而不是「每组固定 N 个」：后者在 shard 数不整除时会多出一个零头组，
     * 让某条线程空跑一次阻塞读，白白多一个 {@code BLOCK} 周期。
     */
    private List<List<Integer>> splitByConcurrency(BitSet owned) {
        int total = owned.cardinality();
        if (total == 0) {
            return List.of();
        }
        int groupCount = Math.min(total, props.transitionDrainShardConcurrency());
        List<List<Integer>> groups = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            groups.add(new ArrayList<>(total / groupCount + 1));
        }
        int index = 0;
        for (int shard = owned.nextSetBit(0); shard >= 0; shard = owned.nextSetBit(shard + 1)) {
            groups.get(index++ % groupCount).add(shard);
        }
        return groups;
    }

    /**
     * 线程名按<b>池槽位</b>而非 shard 编号命名 —— 线程被复用，不再与某个 shard 一一对应。
     * shard 归属仍可从日志正文定位：本类所有日志都带 {@code shard=} 字段。
     */
    private static ThreadFactory shardThreadFactory() {
        AtomicInteger slot = new AtomicInteger();
        return runnable -> Thread.ofPlatform()
            .name("online-transition-shard-" + slot.getAndIncrement())
            .unstarted(runnable);
    }

    /**
     * 排空一组 shard：<b>一次多流 {@code XREADGROUP}</b>，再按 shard 分别落库。
     *
     * <p>{@code XAUTOCLAIM} 已移出本路径（契约 §10.5.2）—— 它现在由 {@link #sweepLoop} 按固定周期跑。
     * 原实现靠「先认领再读新」的<b>顺序</b>保证滞留项不被新项饿死；改成独立周期后，
     * 认领变成与新项速率无关的<b>时间</b>保证，比原来更强。
     *
     * <p>按 shard 分别 {@code process} 而不是合并成一批：ACK 是按 shard 做的
     * （{@code XACK} 需要 stream key），而且每个 shard 内的顺序必须保持。
     */
    private void drainGroup(List<Integer> shards) {
        Map<Integer, List<PendingOnlineTransition>> fresh = transitionStore.readNew(
            shards, consumerId, props.transitionReadBatchSize(), props.transitionBlockMs());
        for (Map.Entry<Integer, List<PendingOnlineTransition>> entry : fresh.entrySet()) {
            if (!running.get()) {
                return;
            }
            process(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 认领与积压巡检环（契约 §10.5.2）。
     *
     * <p>{@code XAUTOCLAIM} 与 {@code XPENDING} <b>不能留在排空热循环里</b>：热循环合并成多流读之后
     * 转得很快，这两条命令会随之从「每 shard 每轮一次」变成高频空转 —— 256 shard 即每轮 512 次往返。
     * 它们本就是恢复路径与观测路径，按时间周期跑才对。
     *
     * <p>周期取 {@code transition-claim-idle-ms} 的一半：滞留项在超过 idle 阈值后，
     * 最迟再过半个周期就会被接管，接管延迟有明确上界。
     */
    private void sweepLoop() {
        while (running.get()) {
            try {
                // await 而不是 sleep：停机时立即返回 true 并退出，不让 join 白等一个周期
                if (stopSignal.await(sweepIntervalMs(), TimeUnit.MILLISECONDS)) {
                    return;
                }
                BitSet owned = ownership.ownedShards();
                long pendingTotal = 0;
                for (int shard = owned.nextSetBit(0); shard >= 0 && running.get();
                     shard = owned.nextSetBit(shard + 1)) {
                    pendingTotal += sweepShard(shard);
                }
                if (metrics != null) {
                    // 按轮汇总而非按 shard 打标签：256 个 shard 会把监控基数炸掉
                    metrics.onlinePendingTransitions(pendingTotal);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("transition 巡检环异常，继续下一轮", e);
            }
        }
    }

    /**
     * @return 该 shard 的 PEL 积压条数；失败时返回 0（不影响汇总的量级判断）。
     */
    private long sweepShard(int shard) {
        try {
            List<PendingOnlineTransition> claimed = transitionStore.claimStale(
                shard, consumerId, props.transitionClaimIdleMs(), props.transitionReadBatchSize());
            if (!claimed.isEmpty()) {
                log.info("接管滞留 transition: shard={} count={}", shard, claimed.size());
                process(shard, claimed);
            }
            long pending = transitionStore.pendingCount(shard);
            if (pending > props.maxPendingTransitionsAlert()) {
                // 只告警，禁止据此 trim 未 ACK 项 —— 那等于把可靠交接退化成尽力而为
                log.error("transition 积压超阈值，可能发生了平台级事件: shard={} pending={} 阈值={}",
                          shard, pending, props.maxPendingTransitionsAlert());
            }
            return pending;
        } catch (Exception e) {
            // 单个 shard 巡检失败不能中断整轮，否则一个坏 shard 会让其余 255 个都得不到接管
            log.error("巡检 transition shard 失败: shard={}", shard, e);
            return 0;
        }
    }

    /**
     * 巡检周期 = 认领空闲阈值的一半，因此不是独立阈值，跟着 {@code transition-claim-idle-ms} 走。
     */
    private long sweepIntervalMs() {
        return Math.max(1, props.transitionClaimIdleMs() / 2);
    }

    /**
     * 处理一批：解析 deviceId → 写 MySQL + 时序库 → 全成功才 ACK。
     */
    private void process(int shard, List<PendingOnlineTransition> pending) {
        long processStartedAt = System.nanoTime();
        EngineMetadataSnapshot root = metadataSyncService.current();
        if (root == null) {
            log.warn("根快照尚未就绪，本批 transition 稍后重做: shard={} size={}", shard, pending.size());
            return;
        }

        List<OnlineTransition> resolved = resolve(shard, pending, root);
        if (resolved.isEmpty()) {
            return;
        }

        // ── MySQL：批内按 deviceKey 合并，只保留最终态 ──
        // 驱动抖动造成的「在线→未知→在线」往复会坍缩成一次写，不产生无意义的中间态 UPDATE
        Map<String, OnlineTransition> merged = new LinkedHashMap<>();
        for (OnlineTransition transition : resolved) {
            merged.put(transition.deviceKey(), transition);
        }
        List<DeviceStatusUpdate> updates = new ArrayList<>(merged.size());
        for (OnlineTransition transition : merged.values()) {
            updates.add(new DeviceStatusUpdate(transition.deviceId(),
                                               transition.target(),
                                               transition.changedAtMs(),
                                               transition.streamMs(),
                                               transition.streamSeq()));
        }
        BatchResult<DeviceStatusUpdate> mysql = repository.updateDeviceStatusBatch(updates);
        if (mysql.hasRetryable()) {
            // 不 ACK：留在 PEL 里，下一轮或别的实例接管重做
            log.warn("状态落库未全部成功，本批不确认: shard={} 待重试={}", shard, mysql.retryable().size());
            return;
        }

        // ── 时序库：不合并，每次真实跳变都要留痕 ──
        List<OnlineTransition> logged = resolved.stream().filter(OnlineTransition::writesOnlineLog).toList();
        if (!logged.isEmpty()) {
            try {
                onlineLogWriter.write(logged);
            } catch (Exception e) {
                log.error("上下线历史落库失败，本批不确认: shard={} size={}", shard, logged.size(), e);
                return;
            }
        }

        // ── 两个目标都成功，才允许确认 ──
        List<String> ids = new ArrayList<>(resolved.size());
        for (OnlineTransition transition : resolved) {
            ids.add(transition.transitionId());
        }
        transitionStore.acknowledge(shard, ids);
        if (metrics != null) {
            metrics.onlineTransitionBatch(resolved.size(), (System.nanoTime() - processStartedAt) / 1_000_000L);
            // 端到端延迟：Lua 侧 XADD 记的 changedAt 到此刻落库完成。
            // 逐条记而非按批平均 —— 同批内不同设备的跳变时刻可能相差很远
            long now = System.currentTimeMillis();
            for (OnlineTransition t : resolved) {
                metrics.onlineTransitionLag(Math.max(0, now - t.changedAtMs()));
            }
        }
        log.debug("transition 已落库并确认: shard={} 状态={} 历史={} ", shard, updates.size(), logged.size());
    }

    /**
     * 批量把 {@code productKey + deviceCode} 解析成经双版本校验的 {@code deviceId}。
     *
     * <p>用 {@code DeviceMetadataCache.getAll}（L1 → 分桶 L2 → MySQL 批量回源）而不是逐条查询：
     * 一批 500 条各查一次就是把关系库当缓存用。
     */
    private List<OnlineTransition> resolve(int shard, List<PendingOnlineTransition> pending,
                                           EngineMetadataSnapshot root) {
        Set<DeviceRef> refs = new LinkedHashSet<>(pending.size());
        for (PendingOnlineTransition item : pending) {
            refs.add(new DeviceRef(item.productKey(), item.deviceCode()));
        }
        Map<DeviceRef, DeviceRuntimeMeta> devices;
        try {
            devices = deviceCache.getAll(refs, root);
        } catch (Exception e) {
            // 回源预算耗尽等：不 ACK，等下一轮
            log.error("解析 transition 设备失败，本批稍后重做: shard={} size={}", shard, pending.size(), e);
            return List.of();
        }

        List<OnlineTransition> resolved = new ArrayList<>(pending.size());
        List<String> vanished = new ArrayList<>();
        for (PendingOnlineTransition item : pending) {
            DeviceRef ref = new DeviceRef(item.productKey(), item.deviceCode());
            DeviceRuntimeMeta device = devices.get(ref);
            if (device == null) {
                // 查不到 ≠ 已删除：Bloom 的否定也可能只是本实例尚未收敛（metadata-sync-bus.md §6.8bis）。
                // 已实测确认新建设备在目录收敛前 mightContain 返回 false，getAll 会短路到不回源 MySQL。
                // 因此在执行破坏性动作（evict 租约 + 丢弃跳变）之前，必须先让修复协调器
                // 走完「head-probe → 等待收敛 → 用新根重试」，只有它返回 null 才是可以下的结论。
                DeviceRuntimeMeta repaired;
                try {
                    repaired = unknownDeviceRepair.repairOrNull(ref);
                } catch (RuntimeException e) {
                    // 依赖失败或等待超时：不确认、不清理，等下一轮重做。
                    // 把一次收敛延迟固化成「设备已删除」会真的丢掉上下线历史
                    log.error("解析 transition 设备时修复失败，本条稍后重做: deviceKey={}", item.deviceKey(), e);
                    continue;
                }
                if (repaired == null) {
                    // 追平水位后仍然没有：设备确实已被删除，这条跳变永远无法落库。
                    // 必须确认掉并清理 Redis 残留，否则它会在 PEL 里被反复接管，成为永不消失的幽灵项
                    vanished.add(item.transitionId());
                    leaseStore.evict(item.deviceKey());
                    continue;
                }
                device = repaired;
            }
            resolved.add(new OnlineTransition(
                shard, item.transitionId(), item.streamMs(), item.streamSeq(),
                device.deviceId(), item.deviceKey(), item.productKey(), item.deviceCode(),
                item.target(), item.reason(), item.changedAtMs()));
        }
        if (!vanished.isEmpty()) {
            log.warn("transition 指向已删除设备，已确认并清理: shard={} count={}", shard, vanished.size());
            transitionStore.acknowledge(shard, vanished);
        }
        return resolved;
    }

    @Override
    public void stop() {
        // 置位后驱动循环不再开新一轮；当轮的 XREADGROUP BLOCK 最坏 transition-block-ms 就返回
        running.set(false);
        // 唤醒巡检线程的周期等待，避免它睡满一个周期把停机预算耗光（见 stopSignal 字段说明）
        stopSignal.countDown();
        // 必须「等停」而不是「叫停」：stop() 一返回 Vert.x 就继续 close()，拆掉它自己创建的
        // Redis/MySQL 传输层。原实现只 interrupt + shutdownNow 就返回，在途的 drainGroup
        // 随即撞上被拆掉的连接，每次停机固定刷一批 CONNECTION_CLOSED
        // （vertx-pool-thread-affinity.md §3.4bis）
        VerticleQuiesce.join("transition 驱动线程", driver);
        // 巡检环同样等停：它也会经 process() 写 MySQL 与时序库。
        // stopSignal 已把它从周期等待中唤醒，因此它只需跑完当前这一轮 sweepShard 就退出，
        // 不会撞上 VerticleQuiesce 的超时中断路径
        VerticleQuiesce.join("transition 巡检线程", sweeper);
        // 驱动线程退出时本轮 shard 已全部 get() 完，池里不会再有在途任务；
        // 万一有，也让它把这批 ACK 掉再走 —— 半途中断只是把它推给别的实例 XAUTOCLAIM 重做
        VerticleQuiesce.shutdown("transition shard 线程池", shardWorkers);
        log.info("transition drainer 已停止");
    }
}

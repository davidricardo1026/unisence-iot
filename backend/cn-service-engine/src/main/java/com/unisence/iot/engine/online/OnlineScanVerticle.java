package com.unisence.iot.engine.online;

import com.unisence.iot.engine.config.EngineOnlineProperties;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.verticle.VerticleQuiesce;
import io.vertx.core.AbstractVerticle;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 租约判活扫描（device-online-state-design.md §六）。
 *
 * <p>按 rendezvous 归属只扫描本实例负责的 shard，并以 per-shard token 做最终单活保护。
 * <b>不再有全局单活锁</b>：那会让百万设备的判活串行在一个实例上，而 256 个 shard 本可以
 * 分摊到整个集群。
 *
 * <p>判定出的跳变由 Lua 直接写进 transition stream，本 verticle <b>不负责落库</b> ——
 * 落库是 {@link OnlineTransitionVerticle} 的职责，且要等 MySQL 与时序库都成功才确认。
 */
@Slf4j
public class OnlineScanVerticle extends AbstractVerticle {

    private final DeviceLeaseStore leaseStore;
    private final ShardOwnership ownership;
    private final EngineOnlineProperties props;
    /**
     * 可为 {@code null}：指标为可选装配。
     */
    private final EngineMetrics metrics;
    private final String instanceId;

    /**
     * 判活链路的全部线程都必须是<b>长生命周期</b>的。
     *
     * <p>本类的每一步都经 {@code DeviceLeaseStore} 打 Redis，而 Vert.x Redis 客户端
     * （{@code RedisConnectionManager$RedisEndpoint}）用的是与 SQL 客户端<b>同一个</b>
     * {@code io.vertx.core.internal.pool.ConnectionPool}，且未覆盖 selector ——
     * 默认判据同样是 {@code SAME_EVENT_LOOP_SELECTOR}：只复用 event loop 与调用方相同的连接，
     * 而调用方 event loop 由 {@code VertxImpl.stickyEventLoop()} 按线程 ID 取模决定。
     * 每轮新建线程 ⇒ 线程 ID 每轮都变 ⇒ 复用永不命中 ⇒ Redis 连接被反复重建
     * （metadata-sync-mysql-connection-exhaustion-incident.md §四bis，MySQL 侧同因同果）。
     */
    private ExecutorService scanDriver;
    private ExecutorService shardWorkers;
    private ScheduledExecutorService lockRenewers;
    /**
     * 上一轮扫描是否仍在进行。
     *
     * <p>原实现每个 tick 新建一条线程、不做在途判定，扫描超时会自然重叠（靠 shard 锁兜底）。
     * 改用固定线程后若不加此判定，超时的轮次会在队列里无界堆积 —— 跳过比堆积安全：
     * 下一个 tick 马上还会再来，而堆积会让扫描永远追不上。
     */
    private final AtomicBoolean scanInFlight = new AtomicBoolean();
    /**
     * 停机已发起。置位后在途轮次不再提交新 shard —— 见 {@link #runScan()} 内的让路判定。
     */
    private final AtomicBoolean stopping = new AtomicBoolean();
    private long timerId = -1;

    public OnlineScanVerticle(DeviceLeaseStore leaseStore,
                              ShardOwnership ownership,
                              EngineOnlineProperties props,
                              String instanceId) {
        this(leaseStore, ownership, props, instanceId, null);
    }

    public OnlineScanVerticle(DeviceLeaseStore leaseStore,
                              ShardOwnership ownership,
                              EngineOnlineProperties props,
                              String instanceId, EngineMetrics metrics) {
        this.metrics = metrics;
        this.leaseStore = leaseStore;
        this.ownership = ownership;
        this.props = props;
        this.instanceId = instanceId;
    }

    @Override
    public void start() {
        int concurrency = props.scanShardConcurrency();
        scanDriver = Executors.newSingleThreadExecutor(namedVirtualFactory("online-scan"));
        // 池容量即并发上限，取代原先的 Semaphore：封顶发生在提交之前（提交满了就排队）
        shardWorkers = Executors.newFixedThreadPool(concurrency, namedVirtualFactory("online-scan-shard"));
        // 同时最多 concurrency 个 shard 持锁，续期器按同一上限配备，各 shard 互不阻塞
        lockRenewers = Executors.newScheduledThreadPool(
            concurrency, namedVirtualFactory("online-scan-lock-renew"));
        timerId = vertx.setPeriodic(props.scanIntervalMs(), id -> runScanSafely());
        log.info("OnlineScanVerticle 已启动: interval={}ms batchSize={} shardConcurrency={} instanceId={}",
                 props.scanIntervalMs(), props.scanBatchSize(), props.scanShardConcurrency(), instanceId);
    }

    /**
     * 线程工厂：每个池的线程都长期存活，名字带序号而非 shard 编号 —— 线程被复用，
     * 不再与某个 shard 一一对应；shard 归属仍可从日志正文的 {@code shard=} 字段定位。
     */
    private static ThreadFactory namedVirtualFactory(String prefix) {
        AtomicInteger slot = new AtomicInteger();
        return runnable -> Thread.ofVirtual()
            .name(prefix + "-" + slot.getAndIncrement())
            .unstarted(runnable);
    }

    private void runScanSafely() {
        // 扫描全程是阻塞 Redis 调用，必须离开定时器回调所在的 verticle 队列；
        // 但只能交给固定的驱动线程，不能每轮新建 —— 见 scanDriver 字段说明
        if (!scanInFlight.compareAndSet(false, true)) {
            log.warn("上一轮判活扫描尚未结束，跳过本轮: interval={}ms", props.scanIntervalMs());
            return;
        }
        scanDriver.execute(() -> {
            try {
                runScan();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 判活失败不能让定时器停摆：下一轮还要继续，否则设备会永远停在在线态
                log.error("判活扫描异常", e);
            } finally {
                scanInFlight.set(false);
            }
        });
    }

    private void runScan() throws InterruptedException {
        long now = System.currentTimeMillis();
        Set<String> deadServices = leaseStore.expiredServices(now);
        if (!deadServices.isEmpty()) {
            log.warn("检测到失联驱动实例，其名下设备将判为「未知」而非「离线」: count={} instances={}",
                     deadServices.size(), deadServices);
        }

        BitSet owned = ownership.ownedShards();
        AtomicInteger offline = new AtomicInteger();
        AtomicInteger unknown = new AtomicInteger();
        List<Future<?>> workers = new ArrayList<>();
        for (int shard = owned.nextSetBit(0); shard >= 0; shard = owned.nextSetBit(shard + 1)) {
            if (stopping.get()) {
                break;
            }
            int target = shard;
            workers.add(shardWorkers.submit(() -> {
                // 停机已发起：队列里还没开跑的 shard 直接放弃本轮。判活是周期动作，
                // 少扫一轮只是延后一个 scan-interval，而把整轮跑完会花光停机预算
                if (stopping.get()) {
                    return;
                }
                try {
                    scanShard(target, now, deadServices, offline, unknown);
                } catch (Exception e) {
                    log.error("扫描 shard 失败: shard={}", target, e);
                }
            }));
        }
        for (Future<?> worker : workers) {
            try {
                worker.get();
            } catch (ExecutionException e) {
                // 任务体已自行兜底 Exception，走到这里说明抛的是 Error
                log.error("扫描 shard 任务异常终止", e.getCause());
            }
        }
        if (offline.get() > 0 || unknown.get() > 0) {
            log.info("判活结果: 判离线={} 判未知={}", offline.get(), unknown.get());
        }
    }

    /**
     * 扫描单个 shard。
     *
     * <p>token 含本轮唯一 ULID：释放时 compare-and-delete，绝不会删掉别人刚抢到的锁。
     * 取不到锁就跳过 —— <b>禁止等待锁</b>，那会让一个慢 shard 拖住其余全部。
     */
    private void scanShard(int shard, long nowMs, Set<String> deadServices,
                           AtomicInteger offline, AtomicInteger unknown) {
        String token = instanceId + ":" + UUID.randomUUID();
        if (!leaseStore.tryAcquireScanLock(shard, token, props.scanLockTtlMs())) {
            return;
        }
        // 只计持锁期间：判据是「扫描耗时 vs 锁 TTL」，未取到锁的那次不属于扫描
        long scanStartedAt = System.nanoTime();
        try (ScanLockLease lease = new ScanLockLease(shard, token)) {
            lease.start();
            while (true) {
                if (!lease.owned()) {
                    log.warn("扫描锁已丢失，停止 shard 扫描: shard={} token={}", shard, token);
                    return;
                }
                List<String> expired = leaseStore.expiredDevices(shard, nowMs, props.scanBatchSize());
                if (expired.isEmpty()) {
                    return;
                }
                expireBatch(shard, expired, nowMs, deadServices, offline, unknown, lease);
                // 取不满说明已清空；取满则立即续跑，避免积压跨越多个扫描周期
                if (expired.size() < props.scanBatchSize()) {
                    return;
                }
            }
        } finally {
            leaseStore.releaseScanLock(shard, token);
            if (metrics != null) {
                metrics.onlineScanShard((System.nanoTime() - scanStartedAt) / 1_000_000L);
            }
        }
    }

    private void expireBatch(int shard, List<String> deviceKeys, long nowMs, Set<String> deadServices,
                             AtomicInteger offline, AtomicInteger unknown, ScanLockLease lease) {
        Map<String, String> owners = leaseStore.owners(shard, deviceKeys);

        for (String deviceKey : deviceKeys) {
            if (!lease.owned()) {
                return;
            }
            String owner = owners.get(deviceKey);
            // 归属驱动失联 → 是驱动的问题，不是设备掉线
            boolean ownerDead = owner == null || deadServices.contains(owner);
            DeviceOnlineState target = ownerDead ? DeviceOnlineState.UNKNOWN : DeviceOnlineState.OFFLINE;

            // 重新校验分数：扫描取出候选后、判定前设备可能刚好续租。
            // 跳变已由同一 Lua 写入 transition stream，本方法不再负责落库
            if (!leaseStore.expireDevice(deviceKey, nowMs, target)) {
                continue;
            }
            if (target == DeviceOnlineState.OFFLINE) {
                offline.incrementAndGet();
            } else {
                unknown.incrementAndGet();
            }
        }
    }

    /**
     * shard 扫描锁的续期守护。任何续期异常都按“所有权未知”处理并停止扫描，
     * 禁止在无法证明仍持锁时继续产生跳变。
     */
    private final class ScanLockLease implements AutoCloseable {
        private final int shard;
        private final String token;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean owned = new AtomicBoolean(true);
        private ScheduledFuture<?> renewal;

        private ScanLockLease(int shard, String token) {
            this.shard = shard;
            this.token = token;
        }

        /**
         * 续期改为共享调度器上的周期任务，不再为每个 shard、每一轮新建线程。
         *
         * <p>续期本身就是周期动作，用 {@code scheduleAtFixedRate} 表达比「起一条线程跑
         * {@code while + Thread.sleep}」更贴近语义，也顺带消除了本类最后一处每轮新建线程。
         */
        private void start() {
            running.set(true);
            long renewIntervalMs = Math.max(1L, props.scanLockTtlMs() / 3L);
            renewal = lockRenewers.scheduleAtFixedRate(() -> {
                if (!running.get()) {
                    return;
                }
                try {
                    if (!leaseStore.renewScanLock(shard, token, props.scanLockTtlMs())) {
                        // 锁已易主：置位后由扫描主循环的 owned() 判定停止，禁止在此继续产生跳变
                        owned.set(false);
                    }
                } catch (Exception error) {
                    owned.set(false);
                    log.error("续期扫描锁失败，停止该 shard 扫描: shard={} token={}", shard, token, error);
                }
            }, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
        }

        private boolean owned() {
            return owned.get();
        }

        @Override
        public void close() {
            running.set(false);
            if (renewal != null) {
                // 不打断在途续期：它至多是一次 Redis 往返，等它自己结束比中断后
                // 「不知道锁到底续上没有」更安全
                renewal.cancel(false);
            }
        }
    }

    @Override
    public void stop() {
        stopping.set(true);
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
        }
        // 顺序：先停驱动，再停 shard 线程，最后停续期器 ——
        // 反过来会让仍在扫描的 shard 失去续期、误判为「锁已丢失」而刷一片告警。
        // 三者都必须「等停」：原实现只 shutdownNow 就返回，三个池实际是同时死的，
        // 上面这条顺序从未真正生效，而 stop() 返回后 Vert.x 随即拆掉 Redis 传输层
        // （vertx-pool-thread-affinity.md §3.4bis）
        VerticleQuiesce.shutdown("判活驱动线程", scanDriver);
        VerticleQuiesce.shutdown("判活 shard 线程池", shardWorkers);
        VerticleQuiesce.shutdown("扫描锁续期器", lockRenewers);
        log.info("OnlineScanVerticle 已停止");
    }
}

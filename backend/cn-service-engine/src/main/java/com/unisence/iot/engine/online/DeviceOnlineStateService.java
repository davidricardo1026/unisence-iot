package com.unisence.iot.engine.online;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.unisence.iot.common.batch.BatchResult;
import com.unisence.iot.engine.config.EngineOnlineProperties;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.verticle.ThrottledErrorLog;
import com.unisence.iot.message.DeviceMessage;
import com.unisence.iot.message.ServiceHeartbeatMessage;
import com.unisence.iot.metadata.MetadataSyncService;
import com.unisence.iot.metadata.ProductRuntimeMeta;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 设备在线状态机（device-online-state-design.md §五、§七）。
 *
 * <p>三层削峰，缺一不可：
 * <ol>
 *   <li><b>进程内节流</b>：同一设备在 {@code renew-throttle-ms} 内只真正续租一次。
 *       租约表达「最近有活动」，精度只需远小于 TTL —— TTL 180 秒、设备每秒上报时，
 *       逐条续租等于一个窗口内写 180 次，其中 179 次完全多余；</li>
 *   <li><b>续租攒批</b>：未命中节流的续租进 pipeline，一次往返执行多条 Lua；</li>
 *   <li><b>跳变可靠交接</b>：跳变由 Lua 在状态切换的<b>同一脚本内</b> {@code XADD} 进 transition stream，
 *       由 {@link OnlineTransitionVerticle} 批量落库，并在 MySQL 与时序库都成功后才 {@code XACK}。
 *       「仅跳变时更新」只约束稳态速率，不约束突发 —— 驱动重启会让名下数百台设备在同一窗口集体跳变；
 *       而跳变<b>禁止先放进仅进程内的队列</b>（契约 §7.1），那样进程退出就会丢项。</li>
 * </ol>
 *
 * <p><b>节流不会吞掉跳变</b>：设备停报期间没有消息，本地缓存必然已过期，
 * 因此恢复后的第一条消息必定未命中、走完整 Lua、正确产生上线跳变。
 */
@Slf4j
public class DeviceOnlineStateService implements AutoCloseable {

    /**
     * 批次重试上限；超限转毒药，避免永久失败的批次堵死链路。
     */
    private static final int MAX_BATCH_RETRIES = 5;

    private final DeviceLeaseStore leaseStore;
    private final MetadataSyncService metadataSyncService;
    private final EngineOnlineProperties props;
    /**
     * 可为 {@code null}：指标为可选装配。
     */
    private final EngineMetrics metrics;

    /**
     * 节流缓存：值无意义，只用 key 的存在性与 expireAfterWrite。
     */
    private final Cache<String, Boolean> renewThrottle;
    private final BatchAccumulator<LeaseRenewal> renewFlusher;
    /**
     * 续租丢弃的节流告警器。窗口取 {@code renew-flush-delay-ms} 的 25 倍（5 秒）——
     * 须显著大于一次 flush 周期，否则每个周期都打一条，抑制形同虚设。
     *
     * <p>用 warn 而非 error：丢续租是**设计内且有界**的降级
     * （代价上限为一次误判离线，下条上行消息即拉回），不是故障。
     */
    private final ThrottledErrorLog renewOverflowLog;

    public DeviceOnlineStateService(DeviceLeaseStore leaseStore,
                                    MetadataSyncService metadataSyncService,
                                    EngineOnlineProperties props) {
        this(leaseStore, metadataSyncService, props, null);
    }

    public DeviceOnlineStateService(DeviceLeaseStore leaseStore,
                                    MetadataSyncService metadataSyncService,
                                    EngineOnlineProperties props,
                                    EngineMetrics metrics) {
        this.metrics = metrics;
        this.leaseStore = leaseStore;
        this.metadataSyncService = metadataSyncService;
        this.props = props;

        this.renewThrottle = Caffeine.newBuilder()
            .expireAfterWrite(props.renewThrottleMs(), TimeUnit.MILLISECONDS)
            .maximumSize(props.renewThrottleMaxEntries())
            .build();
        // 只剩续租一条链路使用内存累积器 —— 它是三条里唯一「可重建、可丢」的载荷：
        // 丢了最多让设备被短暂误判离线，下条上行消息就会把它拉回在线。
        // 状态跳变与上下线历史已改由 transition stream 可靠交接（契约 §7.1），
        // 因为后者是 append-only 历史，内存缓冲无论怎么调参都保证不了不丢。
        this.renewFlusher = new BatchAccumulator<>("online-renew",
                                                   new BatchAccumulator.Config(props.renewFlushDelayMs(),
                                                                               props.renewFlushBatchSize(),
                                                                               props.renewPendingMax(),
                                                                               props.renewFlushDelayMs(),
                                                                               MAX_BATCH_RETRIES),
                                                   this::writeRenewals, this::dropRenewals,
                                                   BatchAccumulator.virtualThreadFactory("online-renew"));
        this.renewOverflowLog = new ThrottledErrorLog(log, props.renewFlushDelayMs() * 25);
        this.renewFlusher.start();
    }

    /**
     * 任意上行设备消息调用。命中节流则零 Redis 交互直接返回。
     *
     * @param receivedAtMs <b>平台接收时间</b>；禁用报文 {@code occurredAt}（§5.1）
     * @param ownerId      {@code serviceName:instanceId}，来自消息的 source
     */
    public void renew(DeviceMessage message, long receivedAtMs, String ownerId) {
        String deviceKey = message.partitionKey();
        if (renewThrottle.getIfPresent(deviceKey) != null) {
            if (metrics != null) {
                metrics.onlineRenewThrottle(true);
            }
            return;
        }
        if (metrics != null) {
            metrics.onlineRenewThrottle(false);
        }
        ProductRuntimeMeta meta = product(message.productKey());
        if (meta == null) {
            // 产品未定义：不续租。第 3 层校验会把这条消息投 DLQ，此处不重复告警
            return;
        }
        renewThrottle.put(deviceKey, Boolean.TRUE);
        long expireAt = receivedAtMs + meta.onlineTtlSeconds() * 1000L;
        offerDroppingOldest(new LeaseRenewal(deviceKey, ownerId, expireAt));
    }

    /**
     * 服务心跳调用，续驱动实例租约。
     */
    public void renewService(ServiceHeartbeatMessage message, long receivedAtMs) {
        String ownerId = message.serviceName() + ":" + message.instanceId();
        leaseStore.renewService(ownerId, receivedAtMs + props.serviceTtlSeconds() * 1000L);
    }

    private BatchResult<LeaseRenewal> writeRenewals(List<LeaseRenewal> renewals) {
        Set<String> transitioned;
        long startedAt = System.nanoTime();
        try {
            transitioned = leaseStore.renewDevices(renewals);
            if (metrics != null) {
                metrics.onlineRenewFlush(renewals.size(), (System.nanoTime() - startedAt) / 1_000_000L);
            }
        } catch (Exception e) {
            // Redis 抖动是典型瞬态失败；续租重投是幂等的（ZADD/HSET 都是覆盖写）
            log.error("续租批量写入失败，待重试: size={}", renewals.size(), e);
            return BatchResult.allRetryable(renewals);
        }
        if (!transitioned.isEmpty()) {
            // 跳变已由 LUA_RENEW 在同一脚本内写进 transition stream，
            // 这里只记录观测；落库是 OnlineTransitionVerticle 的职责
            log.debug("续租触发上线跳变，已入 transition stream: count={}", transitioned.size());
        }
        return BatchResult.allSucceeded(renewals);
    }

    /**
     * {@code productKey.deviceCode} → [productKey, deviceCode]；productKey 定长 6。
     */
    static String[] splitDeviceKey(String deviceKey) {
        int dot = deviceKey.indexOf('.');
        return new String[]{deviceKey.substring(0, dot), deviceKey.substring(dot + 1)};
    }

    @Override
    public void close() {
        // 顺序即依赖顺序：renew 的落地会产生新的 transition，必须先排空它，
        // 否则后两个 flusher 刚关掉就又被塞进新事件
        renewFlusher.close();
    }

    // ── 毒药处置：批次确定性失败或重试超限时的去向，三家语义不同 ──

    /** 续租丢了只是设备可能被短暂误判离线，下条上行消息会把它拉回在线。 */
    /**
     * 队列满时丢最旧。
     *
     * <p>续租是三条链路里唯一可以这样处理的：它只表达「最近有活动」，队列里更新的续租还在，
     * 丢掉一条已被后续续租取代的旧记录，代价上限是一次误判离线，而下条上行消息就会拉回在线。
     *
     * <p>腾位与重入队之间有竞态（并发生产者可能抢走位置），此时退化为丢弃本条 ——
     * 为它加锁会把热路径上的无锁入队变成全局串行点。
     */
    private void offerDroppingOldest(LeaseRenewal renewal) {
        if (renewFlusher.tryOffer(renewal)) {
            return;
        }
        LeaseRenewal evicted = renewFlusher.evictOldest();
        boolean accepted = renewFlusher.tryOffer(renewal);
        // 逐条 error → 节流 warn（「engine 热路径红线」，H21 同型）：
        // 溢出一旦发生就是**持续**的（入队速率 > 排空速率），逐条打会让日志量与消息量同阶；
        // 而这是**设计内、有界**的降级（丢一条已被后续续租取代的旧记录，
        // 代价上限一次误判离线），用 ERROR 逐条打既误导严重性又是洪泛面。
        // 真正需要被看到的是「正在持续丢续租」这个事实 —— 节流窗口内的抑制计数正好表达它。
        renewOverflowLog.warn("续租积压已满(" + renewFlusher.capacity() + ")，丢弃 1 条"
                                  + (accepted ? "最旧记录" : "当前记录"));
        log.debug("续租积压已满，被丢记录={}", accepted ? evicted : renewal);
    }

    private void dropRenewals(List<LeaseRenewal> dropped) {
        // 同上：批次落地失败在下游持续不可用时会连续发生，按条数聚合 + 节流
        renewOverflowLog.warn("续租批次无法落地，丢弃 " + dropped.size()
                                  + " 条：设备可能被短暂误判离线，下条上行消息会拉回");
    }


    /**
     * 产品元数据一律从<b>当前根快照</b>读取，不再维护独立的产品缓存。
     *
     * <p>单独一份产品缓存意味着它可以和物模型、设备目录各自替换，
     * 于是同一条消息可能拿到新产品配旧物模型 —— 根快照的原子替换正是为消除这种组合而存在。
     */
    private ProductRuntimeMeta product(String productKey) {
        return metadataSyncService.product(productKey);
    }

}

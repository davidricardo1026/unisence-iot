package com.unisence.iot.engine.verticle;

import com.unisence.iot.engine.config.EngineIngressProperties;
import com.unisence.iot.engine.config.EngineRouteProperties;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.online.DeviceOnlineStateService;
import com.unisence.iot.engine.route.RouteCandidate;
import com.unisence.iot.engine.route.RouteJsonEncoder;
import com.unisence.iot.engine.route.RouteMetrics;
import com.unisence.iot.engine.storage.EventLogWriter;
import com.unisence.iot.engine.thingmodel.EventValidator;
import com.unisence.iot.engine.thingmodel.ThingModelViolation;
import com.unisence.iot.message.*;
import com.unisence.iot.message.codec.MessageCodec;
import com.unisence.iot.metadata.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.*;

/**
 * 事件链路：消费 {@code iot.event}，处理设备创建、事件、设备心跳与服务心跳。
 *
 * <p><b>与属性链路分开消费</b>：两个 Topic 的 SLA 相反 —— 遥测高频可容忍延迟，事件低频但要快且不能丢。
 * 合用一个 poll 循环会让遥测洪峰把事件顶在队尾（metadata-sync-bus.md「Topic 划分」）。
 *
 * <p><b>本链路承载着在线判定的两个关键信号</b>：
 * {@code service_heartbeat} 续驱动租约、{@code device_heartbeat} 续设备租约。
 * 没有前者，所有驱动都会显示失联，判活会把每台过期设备判成「未知」而非「离线」。
 */
@Slf4j
public class EventIngestionVerticle extends AbstractIngestionVerticle {

    private final EventLogWriter eventLogWriter;
    private final MetadataSyncService metadataSyncService;
    private final DeviceOnlineStateService onlineStateService;
    private final DeviceCreateRepository deviceCreateRepository;
    private final DeviceMetadataCache deviceCache;
    private final UnknownDeviceRepairCoordinator unknownDeviceRepair;
    private final MetadataHintStore metadataHintStore;
    private final DeviceAdmissionFilter admissionFilter;

    public EventIngestionVerticle(EngineIngressProperties props,
                                  MessageCodec messageCodec,
                                  EngineMetrics metrics,
                                  EventLogWriter eventLogWriter,
                                  MetadataSyncService metadataSyncService,
                                  DeviceOnlineStateService onlineStateService,
                                  DeviceCreateRepository deviceCreateRepository,
                                  DeviceMetadataCache deviceCache,
                                  UnknownDeviceRepairCoordinator unknownDeviceRepair,
                                  MetadataHintStore metadataHintStore,
                                  DeviceAdmissionFilter admissionFilter,
                                  int ordinal,
                                  EngineRouteProperties routeProps,
                                  RouteMetrics routeMetrics,
                                  RouteJsonEncoder jsonEncoder) {
        super(props, messageCodec, metrics, ordinal, routeProps, routeMetrics, jsonEncoder);
        this.eventLogWriter = eventLogWriter;
        this.metadataSyncService = metadataSyncService;
        this.onlineStateService = onlineStateService;
        this.deviceCreateRepository = deviceCreateRepository;
        this.deviceCache = deviceCache;
        this.unknownDeviceRepair = unknownDeviceRepair;
        this.metadataHintStore = metadataHintStore;
        this.admissionFilter = admissionFilter;
    }

    /**
     * 确定性拒绝的节流告警器。窗口 5 秒：拒绝风暴时每 5 秒一条并带抑制计数，
     * 既不淹没日志，也不会让「大批消息正在被拒」这件事无人察觉。
     */
    private final ThrottledErrorLog rejectLog = new ThrottledErrorLog(log, 5000);

    @Override
    protected String topic() {
        return props.eventTopic();
    }

    @Override
    protected String groupId() {
        return props.eventGroupId();
    }

    @Override
    protected int maxMessageBytes() {
        return props.maxEventMessageBytes();
    }

    @Override
    protected void handleBatch(List<DecodedRecord> batch) {
        // 建档消息先聚合，批末统一处理：逐条建档时每条各等一轮元数据收敛，
        // 100 台就是 100 轮，会击穿 max.poll.interval.ms（engine-hotpath-optimization.md §10.1）
        Map<DeviceRef, DecodedRecord> creates = new LinkedHashMap<>();
        // 以设备为主体的上行（事件、心跳）须先过准入；建档与服务心跳不适用
        Set<DeviceRef> uplinkRefs = new LinkedHashSet<>();

        // 按批内下标缓存，第二遍直接取用（hotpath-findings.md H12）。
        // 建档消息也存进来：它不参与准入，但下标对齐让第二遍无需再判类型
        DeviceRef[] refByIndex = new DeviceRef[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            DecodedRecord decoded = batch.get(i);
            // 穷尽 switch，刻意不写 default：将来新增第六类消息时此处编译期报错
            switch (decoded.message()) {
                case DeviceEventMessage m -> {
                    refByIndex[i] = new DeviceRef(m.productKey(), m.deviceCode());
                    uplinkRefs.add(refByIndex[i]);
                }
                case DeviceHeartbeatMessage m -> {
                    refByIndex[i] = new DeviceRef(m.productKey(), m.deviceCode());
                    uplinkRefs.add(refByIndex[i]);
                }
                // 驱动实例租约不以设备为主体，不参与准入
                case ServiceHeartbeatMessage m -> {
                }
                // 按 DeviceRef 去重：driver 重发会让同一设备在一批内出现多次，
                // 不去重则批量 INSERT 内部自撞唯一键
                case DeviceCreateMessage m -> {
                    refByIndex[i] = new DeviceRef(m.productKey(), m.deviceCode());
                    creates.putIfAbsent(refByIndex[i], decoded);
                }
                // iot.event 不承载属性遥测；出现说明 driver 投错 Topic
                case DevicePropertyMessage m -> publishDeadLetter(decoded.record(), REASON_TYPE_NOT_ROUTABLE);
            }
        }

        // ── 建档必须先于准入判定 ──
        // DeviceMessage 契约保证同一台设备的创建与上报落进同一分区，因此「创建 + 心跳」
        // 完全可能同批到达。若先判准入，刚要建的设备会被自己判成未注册而丢弃 ——
        // 那正是消息模型刻意用同一分区键换来的因果顺序被实现破坏。
        createDevices(creates);

        Set<DeviceRef> admitted = admissionFilter.admit(uplinkRefs);

        List<EventLogWriter.EventRow> eventRows = new ArrayList<>();
        List<RouteCandidate> eventCandidates = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            DecodedRecord decoded = batch.get(i);
            long receivedAt = decoded.record().timestamp();
            switch (decoded.message()) {
                case DeviceEventMessage m -> {
                    // 第一遍已按同一下标构造，此处必非 null
                    DeviceRef ref = refByIndex[i];
                    if (admitted.contains(ref)) {
                        onlineStateService.renew(m, receivedAt, m.source());
                        validateEvent(decoded.record(), m, eventRows, eventCandidates);
                    } else {
                        log.warn("设备未注册，丢弃事件上报: deviceKey={} msgId={}", ref.deviceKey(), m.msgId());
                        recordDiscarded(EngineMetrics.REASON_DEVICE_UNKNOWN, 1);
                    }
                }
                case DeviceHeartbeatMessage m -> {
                    DeviceRef ref = refByIndex[i];
                    if (admitted.contains(ref)) {
                        // 心跳的唯一职责就是续租：它是「没有业务数据时的保底活性信号」。
                        // status/metrics 由驱动代发，其取值域仍待收敛，本轮不消费
                        onlineStateService.renew(m, receivedAt, m.source());
                    } else {
                        // 未注册设备不得占用在线租约，否则它会一路参与分片扫描与跳变生成
                        log.warn("设备未注册，丢弃心跳: deviceKey={} msgId={}", ref.deviceKey(), m.msgId());
                        recordDiscarded(EngineMetrics.REASON_DEVICE_UNKNOWN, 1);
                    }
                }
                case ServiceHeartbeatMessage m ->
                    // 驱动实例租约。级联判定靠它区分「设备掉线」与「驱动掉线」
                    onlineStateService.renewService(m, receivedAt);
                // 已在第一遍分别处理完毕
                case DeviceCreateMessage m -> {
                }
                case DevicePropertyMessage m -> {
                }
            }
        }
        recordAccepted(eventRows.size());
        eventLogWriter.write(eventRows);
        RuleSnapshot rules = metadataSyncService.current().rules();
        if (!rules.routesById().isEmpty()) {
            routeForwarder.forward(rules, eventCandidates);
        }
    }

    /**
     * 批量建档：按 {@code device-create-batch-size} 切批提交，最后<b>只等一次收敛</b>。
     *
     * <p>收敛是全局 single-flight，等的是<b>水位</b>而非具体设备 —— 因此推进到本批最大水位后
     * 等一次，就能同时看见全部新建设备。这是相对逐条建档的数量级差异，
     * 并行化做不到（§10.2）。
     */
    private void createDevices(Map<DeviceRef, DecodedRecord> creates) {
        if (creates.isEmpty()) {
            return;
        }
        EngineMetadataSnapshot root = metadataSyncService.current();
        if (root == null) {
            throw new IllegalStateException("设备创建时根快照不可用");
        }
        // 缓存命中者按幂等成功跳过，不进事务
        Map<DeviceRef, DeviceRuntimeMeta> cached = deviceCache.getAll(creates.keySet(), root);
        List<DeviceCreateMessage> pending = new ArrayList<>(creates.size());
        creates.forEach((ref, decoded) -> {
            if (!cached.containsKey(ref)) {
                pending.add((DeviceCreateMessage) decoded.message());
            }
        });
        if (pending.isEmpty()) {
            log.debug("本批建档消息全部命中缓存，跳过: count={}", creates.size());
            return;
        }

        Set<DeviceRef> insertedAll = new LinkedHashSet<>();
        Set<DeviceRef> touchedAll = new LinkedHashSet<>();
        Long maxCommitSeq = null;
        int batchSize = props.deviceCreateBatchSize();
        for (int from = 0; from < pending.size(); from += batchSize) {
            List<DeviceCreateMessage> slice = pending.subList(from, Math.min(from + batchSize, pending.size()));
            DeviceCreateBatchCommit commit = deviceCreateRepository.createAllIfAbsent(slice);
            // 预校验被拒者从未进事务，逐条留证
            commit.rejected().forEach((ref, reason) -> publishDeadLetter(creates.get(ref).record(), reason));
            insertedAll.addAll(commit.insertedRefs());
            touchedAll.addAll(commit.deviceIdsByRef().keySet());
            if (commit.commitSeq() != null) {
                maxCommitSeq = maxCommitSeq == null ? commit.commitSeq() : Math.max(maxCommitSeq, commit.commitSeq());
            }
        }
        if (!touchedAll.isEmpty()) {
            unknownDeviceRepair.clearNegative(touchedAll);
        }
        if (maxCommitSeq == null) {
            return;
        }

        // 推进与提示各一次，随后<b>直接返回，不等待本实例可见</b>（metadata-sync-bus.md §6.8 修订）。
        //
        // 设备是否建成由 MySQL 事务裁决，不由本实例缓存可见性裁决。紧随其后的准入判定
        // （admissionFilter.admit）走 getAll → 未命中即 MySQL 回源，而 §6.5bis 保证水位落后时
        // 不会被 Bloom 短路，因此刚提交的设备立刻可解析 —— 等的是缓存可见性，判的是 MySQL 可见性，
        // 二者本就不是同一件事。反而等待会同步阻塞一轮收敛，撞上全量重建时必然超时重放并形成活锁。
        metadataSyncService.observeDesiredHead(maxCommitSeq, MetadataSyncTrigger.DEVICE_CREATE);
        metadataHintStore.publish(maxCommitSeq);
        log.info("设备批量建档完成: 新建={} commitSeq={}", insertedAll.size(), maxCommitSeq);
    }

    private void validateEvent(ConsumerRecord<String, byte[]> record, DeviceEventMessage message,
                               List<EventLogWriter.EventRow> rows, List<RouteCandidate> eventCandidates) {
        try {
            EventValidator.ValidatedEvent validated =
                EventValidator.validate(message, metadataSyncService.thingModel(message.productKey()));
            rows.add(new EventLogWriter.EventRow(message, validated.identifier(), validated.eventType(),
                                                 validated.params()));
            eventCandidates.add(new RouteCandidate(record, message));
        } catch (ThingModelViolation e) {
            // 同 PropertyIngestionVerticle：逐条降 debug，可见性交给节流 warn 与指标（H21）
            log.debug("事件物模型校验失败，整条进 DLQ: msgId={} identifier={} reason={} detail={}",
                      message.msgId(), e.identifier(), e.reason(), e.getMessage());
            rejectLog.warn("事件物模型校验失败，整条进 DLQ: reason=" + e.reason()
                               + " identifier=" + e.identifier());
            publishDeadLetter(record, e.reason());
        }
    }
}

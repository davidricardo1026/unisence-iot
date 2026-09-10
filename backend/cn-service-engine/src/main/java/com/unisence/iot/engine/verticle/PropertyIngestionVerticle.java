package com.unisence.iot.engine.verticle;

import com.unisence.iot.engine.config.EngineIngressProperties;
import com.unisence.iot.engine.config.EngineRouteProperties;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.online.DeviceOnlineStateService;
import com.unisence.iot.engine.repository.EngineWriteException;
import com.unisence.iot.engine.repository.PropertyPoint;
import com.unisence.iot.engine.route.RouteCandidate;
import com.unisence.iot.engine.route.RouteJsonEncoder;
import com.unisence.iot.engine.route.RouteMetrics;
import com.unisence.iot.engine.storage.PropertyLogWriter;
import com.unisence.iot.engine.thingmodel.PropertyValidator;
import com.unisence.iot.engine.thingmodel.ThingModelViolation;
import com.unisence.iot.message.*;
import com.unisence.iot.message.codec.MessageCodec;
import com.unisence.iot.metadata.DeviceRef;
import com.unisence.iot.metadata.MetadataSyncService;
import com.unisence.iot.metadata.RuleSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 存储链路：消费 {@code iot.raw.data}，物模型校验后把属性历史写进统一时序存储。
 *
 * <p><b>不经 {@code iot.normalized} 中间 Topic</b> —— 存储链路与规则 topology 各自订阅上行 Topic，
 * 各自解码校验一次（用 CPU 换网络），省掉中间 Topic 的一次 produce、副本复制、两次 fetch 与 Broker 存储
 * （message-idempotency-design.md §一）。
 *
 * <p><b>本链路不设 Dedup State Store</b>：消息级去重只部署在规则 topology，因为 count/sum 型窗口聚合
 * 无法靠业务唯一键幂等。存储链路的重复由时序库同设备同时间戳覆盖吸收（§六、§九）。
 */
@Slf4j
public class PropertyIngestionVerticle extends AbstractIngestionVerticle {

    private static final String REASON_DOWNSTREAM_REJECTED = "DOWNSTREAM_REJECTED";

    private final PropertyLogWriter propertyLogWriter;
    private final MetadataSyncService metadataSyncService;
    private final DeviceOnlineStateService onlineStateService;
    private final DeviceAdmissionFilter admissionFilter;

    public PropertyIngestionVerticle(EngineIngressProperties props,
                                     MessageCodec messageCodec,
                                     EngineMetrics metrics,
                                     PropertyLogWriter propertyLogWriter,
                                     MetadataSyncService metadataSyncService,
                                     DeviceOnlineStateService onlineStateService,
                                     DeviceAdmissionFilter admissionFilter,
                                     int ordinal,
                                     EngineRouteProperties routeProps,
                                     RouteMetrics routeMetrics,
                                     RouteJsonEncoder jsonEncoder) {
        super(props, messageCodec, metrics, ordinal, routeProps, routeMetrics, jsonEncoder);
        this.propertyLogWriter = propertyLogWriter;
        this.metadataSyncService = metadataSyncService;
        this.onlineStateService = onlineStateService;
        this.admissionFilter = admissionFilter;
    }

    /**
     * 确定性拒绝的节流告警器。窗口 5 秒：拒绝风暴时每 5 秒一条并带抑制计数，
     * 既不淹没日志，也不会让「大批消息正在被拒」这件事无人察觉。
     */
    private final ThrottledErrorLog rejectLog = new ThrottledErrorLog(log, 5000);

    @Override
    protected String topic() {
        return props.rawDataTopic();
    }

    @Override
    protected String groupId() {
        return props.groupId();
    }

    @Override
    protected int maxMessageBytes() {
        return props.maxPropertyMessageBytes();
    }

    @Override
    protected void handleBatch(List<DecodedRecord> batch) {
        // §6.7bis：所有上行信息都以设备为主体，因此先批量判定准入，再续租、再校验物模型。
        // 批量解析而不是逐条 —— 稳态下全部命中 L1，零网络。
        // 按批内下标缓存，第二遍直接取用：原实现两个循环各构造一次 DeviceRef，
        // 每条消息白建一个对象外加两次 productKey()/deviceCode() 调用（hotpath-findings.md H12）
        DeviceRef[] refByIndex = new DeviceRef[batch.size()];
        Set<DeviceRef> refs = new LinkedHashSet<>();
        for (int i = 0; i < batch.size(); i++) {
            if (batch.get(i).message() instanceof DevicePropertyMessage m) {
                DeviceRef ref = new DeviceRef(m.productKey(), m.deviceCode());
                refByIndex[i] = ref;
                refs.add(ref);
            }
        }
        Set<DeviceRef> admitted = admissionFilter.admit(refs);

        List<ConvertedRecord> converted = new ArrayList<>(batch.size());

        for (int i = 0; i < batch.size(); i++) {
            DecodedRecord decoded = batch.get(i);
            // 穷尽 switch，刻意不写 default：将来新增第六类消息时此处编译期报错
            switch (decoded.message()) {
                case DevicePropertyMessage m -> {
                    // 第一遍已按同一下标构造，此处必非 null
                    DeviceRef ref = refByIndex[i];
                    if (admitted.contains(ref)) {
                        // 任意上行消息都续租，不只靠心跳：正在上报属性的设备显然在线。
                        // 用平台接收时间而非报文 occurredAt —— 设备时钟漂移会让租约永不过期或立即过期。
                        onlineStateService.renew(m, decoded.record().timestamp(), m.source());
                        convertProperty(decoded.record(), m, converted);
                    } else {
                        // 确认未注册：记 warn 后丢弃，不落库、不续租、不进 DLQ。
                        // 这类报文没有产品归属和物模型，重放多少次结果都一样，
                        // 进 DLQ 只会淹没真正需要人工介入的毒消息（§6.7bis）
                        log.warn("设备未注册，丢弃属性上报: deviceKey={} msgId={}", ref.deviceKey(), m.msgId());
                        recordDiscarded(EngineMetrics.REASON_DEVICE_UNKNOWN, 1);
                    }
                }
                // iot.raw.data 按契约只承载属性遥测；出现其它类型说明 driver 投错 Topic
                case DeviceCreateMessage m -> publishDeadLetter(decoded.record(), REASON_TYPE_NOT_ROUTABLE);
                case DeviceEventMessage m -> publishDeadLetter(decoded.record(), REASON_TYPE_NOT_ROUTABLE);
                case DeviceHeartbeatMessage m -> publishDeadLetter(decoded.record(), REASON_TYPE_NOT_ROUTABLE);
                case ServiceHeartbeatMessage m -> publishDeadLetter(decoded.record(), REASON_TYPE_NOT_ROUTABLE);
            }
        }
        recordAccepted(converted.size());
        List<ConvertedRecord> written = writeOrIsolate(converted);
        RuleSnapshot rules = metadataSyncService.current().rules();
        if (!rules.routesById().isEmpty()) {
            List<RouteCandidate> candidates = new ArrayList<>(written.size());
            for (int i = 0; i < written.size(); i++) {
                ConvertedRecord item = written.get(i);
                candidates.add(new RouteCandidate(item.record(), item.message()));
            }
            routeForwarder.forward(rules, candidates);
        }
        // 提交边界：时序库写成功即可提交 offset。Redis 最新值层已废弃 ——
        // 当前值由 12 张属性类型×保留档位表的 latest 查询合并派生（latest-property-runtime.md §六）
    }

    private void convertProperty(ConsumerRecord<String, byte[]> record, DevicePropertyMessage message,
                                 List<ConvertedRecord> converted) {
        try {
            // 第 3 层物模型校验：未知 identifier / 类型不符 → 整条拒绝，禁止半条落盘
            PropertyValidator.ValidatedProperties values =
                PropertyValidator.validateForStorage(message, metadataSyncService.thingModel(message.productKey()));
            if (!values.history().isEmpty()) {
                converted.add(new ConvertedRecord(record, message, values.history()));
            }
        } catch (ThingModelViolation e) {
            // 逐条降 debug + 节流 warn：确定性拒绝是**可预期的高频事件**，
            // 每条的完整信息已在 DLQ 记录与 iot_ingress_dlq_total{reason} 里。
            // 逐条 WARN 是第三份拷贝，驱动配错标识符时会瞬间刷爆磁盘（H21）。
            log.debug("物模型校验失败，整条进 DLQ: msgId={} identifier={} reason={} detail={}",
                      message.msgId(), e.identifier(), e.reason(), e.getMessage());
            rejectLog.warn("物模型校验失败，整条进 DLQ: reason=" + e.reason()
                               + " identifier=" + e.identifier());
            publishDeadLetter(record, e.reason());
            recordDiscarded(EngineMetrics.REASON_THING_MODEL, 1);
        }
    }

    /**
     * 整批写；若整批被下游<b>确定性拒绝</b>，降级为逐条写以隔离毒消息。
     *
     * <p>降级只由 {@code retryable=false} 触发，<b>不由失败次数触发</b>：按次数降级时，
     * 一次时序库宕机会让每批都重试到阈值、再逐条全失败，最终把整条流排空进 DLQ。
     */
    private List<ConvertedRecord> writeOrIsolate(List<ConvertedRecord> converted) {
        List<PropertyPoint> all = new ArrayList<>();
        for (ConvertedRecord item : converted) {
            all.addAll(item.history());
        }
        try {
            propertyLogWriter.write(all);
            return converted;
        } catch (EngineWriteException e) {
            if (e.retryable()) {
                throw e;
            }
            log.warn("整批被时序数据库拒绝，降级逐条写以隔离毒消息: size={}", converted.size(), e);
            return isolate(converted);
        }
    }

    /**
     * 逐条写，把被拒绝的单条隔离进 DLQ，其余正常落盘。
     *
     * <p>隔离过程中一旦出现<b>可重试</b>失败，立即中止并整批重放：此时已无法判断后续失败是数据问题
     * 还是环境问题，继续隔离会把好数据误判成毒消息。
     */
    private List<ConvertedRecord> isolate(List<ConvertedRecord> converted) {
        int poisoned = 0;
        List<ConvertedRecord> accepted = new ArrayList<>(converted.size());
        for (ConvertedRecord item : converted) {
            try {
                propertyLogWriter.write(item.history());
                accepted.add(item);
            } catch (EngineWriteException e) {
                if (e.retryable()) {
                    log.error("隔离期间出现可重试故障，中止隔离并整批重放: 已隔离={}", poisoned, e);
                    throw e;
                }
                log.error("单条被时序数据库拒绝，隔离进 DLQ: partition={} offset={}",
                          item.record().partition(), item.record().offset(), e);
                publishDeadLetter(item.record(), REASON_DOWNSTREAM_REJECTED);
                poisoned++;
            }
        }
        log.warn("毒消息隔离完成: 总数={} 进 DLQ={}", converted.size(), poisoned);
        return accepted;
    }

    private record ConvertedRecord(ConsumerRecord<String, byte[]> record,
                                   DevicePropertyMessage message,
                                   List<PropertyPoint> history) {
    }
}

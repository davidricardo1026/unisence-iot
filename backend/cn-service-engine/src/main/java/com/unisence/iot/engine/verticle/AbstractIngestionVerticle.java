package com.unisence.iot.engine.verticle;

import com.unisence.iot.engine.config.EngineIngressProperties;
import com.unisence.iot.engine.config.EngineRouteProperties;
import com.unisence.iot.engine.kafka.DlqPublisher;
import com.unisence.iot.engine.kafka.EngineProducerFactory;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.route.RouteForwarder;
import com.unisence.iot.engine.route.RouteJsonEncoder;
import com.unisence.iot.engine.route.RouteMetrics;
import com.unisence.iot.message.IotMessage;
import com.unisence.iot.message.codec.MessageCodec;
import com.unisence.iot.message.codec.MessageDecodeException;
import io.vertx.core.AbstractVerticle;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上行消费的共用骨架：poll 循环、手动 offset、回退重放、退避、DLQ、有界并发洗数。
 *
 * <p>抽出基类而非在两条链路各写一遍，是因为这些逻辑<b>微妙且易错</b> ——
 * 「DLQ 先于业务落盘、业务落盘先于 commit」的顺序、失败时按分区回退到批起点、
 * 退避不是放弃计时器 —— 复制一份就意味着两处各自演化，bug 只在其中一处修好。
 *
 * <p>子类只提供「订阅哪个 Topic、用哪个消费组、单条多大算超尺寸、一批解码后怎么处理」。
 *
 * <p><b>两条链路必须是独立消费者</b>：`iot.raw.data` 是高频洪流、`iot.event` 低频但要快，
 * 合用一个 poll 循环会让遥测积压把事件顶在队尾（metadata-sync-bus.md「Topic 划分」）。
 */
@Slf4j
public abstract class AbstractIngestionVerticle extends AbstractVerticle {

    /**
     * Kafka record header：消息类型。与 driver 侧 {@code DeviceDataPublisher.HEADER_MESSAGE_TYPE} 同源。
     */
    protected static final String HEADER_MESSAGE_TYPE = "mt";

    protected static final String REASON_MISSING_TYPE_HEADER = "MISSING_MT_HEADER";
    protected static final String REASON_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    protected static final String REASON_TYPE_NOT_ROUTABLE = "MESSAGE_TYPE_NOT_ROUTABLE";

    /**
     * 消费链路不可恢复时的进程退出码，便于编排层与运维脚本区分「消费链路死亡」与其它退出原因。
     */
    private static final int EXIT_INGESTION_DEAD = 70;

    protected final EngineIngressProperties props;
    protected final MessageCodec messageCodec;
    /**
     * 可为 {@code null}：指标为可选装配，缺失时摄入行为完全不变。
     */
    protected final EngineMetrics metrics;
    /**
     * 本消费者在同链路内的序号（0 起）。
     *
     * <p><b>存在的唯一理由是静态成员 ID 必须唯一</b>：同一链路部署多个消费者时，
     * 若它们共用一个 {@code group.instance.id}，Kafka 会认为后来者是「同一成员重连」
     * 而把先到的踢出组 —— 表现为消费者反复上下线、分区反复重分配，且**不报错**。
     * 线程名也带上它，便于在 jstack / arthas 里区分是哪一个 poll 循环。
     */
    private final int ordinal;
    private final EngineRouteProperties routeProps;
    private final RouteMetrics routeMetrics;
    private final RouteJsonEncoder jsonEncoder;

    private KafkaConsumer<String, byte[]> consumer;
    private KafkaProducer<String, byte[]> producer;
    private DlqPublisher dlqPublisher;
    protected RouteForwarder routeForwarder;
    private Thread pollThread;
    private volatile boolean running;
    private int consecutiveFailures;

    protected AbstractIngestionVerticle(EngineIngressProperties props, MessageCodec messageCodec) {
        this(props, messageCodec, null);
    }

    protected AbstractIngestionVerticle(EngineIngressProperties props, MessageCodec messageCodec,
                                        EngineMetrics metrics) {
        this(props, messageCodec, metrics, 0);
    }

    protected AbstractIngestionVerticle(EngineIngressProperties props, MessageCodec messageCodec,
                                        EngineMetrics metrics, int ordinal) {
        this(props, messageCodec, metrics, ordinal, null, null, null);
    }

    protected AbstractIngestionVerticle(EngineIngressProperties props, MessageCodec messageCodec,
                                        EngineMetrics metrics, int ordinal,
                                        EngineRouteProperties routeProps, RouteMetrics routeMetrics,
                                        RouteJsonEncoder jsonEncoder) {
        this.props = props;
        this.messageCodec = messageCodec;
        this.metrics = metrics;
        this.ordinal = ordinal;
        this.routeProps = routeProps;
        this.routeMetrics = routeMetrics;
        this.jsonEncoder = jsonEncoder;
    }

    /**
     * 子类在确认丢弃时调用；{@code reason} 只允许 {@code EngineMetrics.REASON_*} 常量。
     */
    protected void recordDiscarded(String reason, int count) {
        if (metrics != null) {
            metrics.ingressDiscarded(topic(), reason, count);
        }
    }

    /**
     * 子类在一批中通过校验、进入落库的记录数。
     */
    protected void recordAccepted(int count) {
        if (metrics != null) {
            metrics.ingressAccepted(topic(), count);
        }
    }

    protected abstract String topic();

    protected abstract String groupId();

    /**
     * 解码前的载荷大小闸门：解码一条超大报文本身就是攻击面。
     */
    protected abstract int maxMessageBytes();

    /**
     * 处理一批已解码消息。
     *
     * <p>调用时基类已把解码期的死信投完。实现可继续用 {@link #publishDeadLetter} 投递业务判定的死信，
     * 但必须在返回前完成 —— 返回即意味着「可以提交 offset」。
     *
     * @throws RuntimeException 抛出即整批不提交、回退重放
     */
    protected abstract void handleBatch(List<DecodedRecord> batch);

    /**
     * 供子类在业务判定失败时投递死信。
     */
    protected void publishDeadLetter(ConsumerRecord<String, byte[]> record, String reason) {
        dlqPublisher.publish(record, reason);
    }

    @Override
    public void start() {
        consumer = new KafkaConsumer<>(consumerConfig());
        producer = EngineProducerFactory.create(
            props.bootstrapServers(), "engine-" + topic() + "-" + ordinal, routeProps);
        dlqPublisher = new DlqPublisher(producer, props.dlqTopic(), groupId());
        routeForwarder = new RouteForwarder(producer, jsonEncoder, routeMetrics);
        running = true;

        pollThread = Thread.ofVirtual().name("engine-poll-" + topic() + "-" + ordinal).unstarted(this::runPollLoop);
        pollThread.start();
        log.info("{} 已启动: topic={} group={} maxPoll={} offsetReset={}",
                 getClass().getSimpleName(), topic(), groupId(),
                 props.maxPollRecords(), props.autoOffsetReset());
    }

    private Map<String, Object> consumerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        // 手动提交：下游写成功后才 commit（architecture-guide.md 轨道一第 5 条）
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.maxPollRecords());
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, props.fetchMinBytes());
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, props.fetchMaxWaitMs());
        // 存储链路默认 earliest：latest 会在新消费组首次启动或 offset 过期时
        // 静默跳过全部积压且无任何告警，与「把所有遥测存进时序库」的语义直接冲突
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.autoOffsetReset());
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                   List.of(CooperativeStickyAssignor.class.getName()));
        if (props.groupInstanceId() != null && !props.groupInstanceId().isBlank()) {
            // 静态成员 ID 必须按消费组区分，否则两个消费者会互相踢掉对方
            // 序号不可省：同链路多消费者共用一个静态成员 ID 时，Kafka 会把先到的踢出组
            config.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG,
                       props.groupInstanceId() + "-" + topic() + "-" + ordinal);
        }
        return config;
    }

    /**
     * poll 主循环。<b>循环存活是本类的首要职责</b>：链路静默停流而进程仍然健康，
     * 是所有故障形态里最难被发现的一种 —— 编排层的存活探针看不出来，
     * 监控只看到「LAG 持续增长但服务在线」，而 LAG 告警通常被当作扩容信号。
     *
     * <p>因此每一轮的两个阶段都各自封闭，任何一轮失败都只影响那一轮：
     * <ul>
     *   <li>{@code poll} 失败 → 退避后重试，不退出循环；</li>
     *   <li>处理/提交失败 → 回退 offset 整批重放（见 {@link #backoff()} 的取舍说明）。</li>
     * </ul>
     *
     * <p>两处都捕获 {@link Throwable} 而不是 {@code Exception}：
     * {@code io.vertx.redis.client.impl.types.ErrorType} <b>继承自 {@code Throwable}</b>，
     * {@code catch (Exception)} 拦不住它。这不是假想 —— 一条 Lua 报错曾就此穿透两层 catch
     * 并终止本线程，让属性链路永久停摆。JVM 级 {@link Error} 仍原样放行。
     */
    private void runPollLoop() {
        boolean unrecoverable = false;
        try {
            consumer.subscribe(List.of(topic()));
            while (running) {
                pollAndProcessOnce();
            }
        } catch (WakeupException e) {
            log.info("{} 收到关闭信号，退出 poll 循环", getClass().getSimpleName());
        } catch (Throwable t) {
            // 能走到这里的只剩 JVM 级 Error 与 subscribe 失败：本链路已不可能自愈。
            // 此时最危险的选择是「记条日志然后让线程悄悄死掉」—— 那正是半死不活状态的来源。
            // 主动终止进程，把恢复交给编排层：无状态消费者的重启代价只是一次 rebalance，
            // 而带病运行的数据缺口没有上界。
            unrecoverable = true;
            log.error("{} poll 循环异常终止，进程退出以避免静默停流: topic={}",
                      getClass().getSimpleName(), topic(), t);
        } finally {
            closeQuietly();
        }
        // 退出码在资源释放之后发出：halt 不跑关闭钩子，放在 finally 之前会漏掉 consumer 关闭。
        // 用 halt 而非 exit —— JVM 已处于不可信状态，不能指望关闭钩子还能正常跑完
        if (unrecoverable) {
            Runtime.getRuntime().halt(EXIT_INGESTION_DEAD);
        }
    }

    /**
     * 一轮 poll + 处理。异常在此闭合，绝不外泄到 {@link #runPollLoop} 的 while 之外。
     */
    private void pollAndProcessOnce() {
        ConsumerRecords<String, byte[]> records;
        try {
            records = consumer.poll(Duration.ofMillis(props.pollTimeoutMs()));
        } catch (WakeupException e) {
            // 关闭信号，交由上层退出循环
            throw e;
        } catch (Throwable t) {
            rethrowIfJvmError(t);
            // poll 自身失败（broker 不可达、元数据刷新失败、鉴权瞬时错误等）：
            // 退避重试而不是退出。真正不可恢复的配置错误会持续失败并持续告警，
            // 那是可观测的；而退出循环是不可观测的
            consecutiveFailures++;
            log.error("poll 失败，退避后重试: topic={} 连续失败={}", topic(), consecutiveFailures, t);
            backoff();
            return;
        }
        if (records.isEmpty()) {
            return;
        }
        try {
            processBatch(records);
            consumer.commitSync();
            consecutiveFailures = 0;
        } catch (WakeupException e) {
            // 停机信号，不是批处理失败。`stop()` 的 consumer.wakeup() 可能落在 commitSync() 上，
            // 若按失败处理会：① 打一条 ERROR「本批处理失败」伪告警（停机路径禁止伪告警）；
            // ② 回退 offset 并 backoff() 睡最多 retry-backoff-ms，白白吃掉停机预算。
            // 本批未 commit，offset 自然停在批起点，交由下次启动或别的实例重放即可。
            //
            // 撞上的概率与单批耗时成正比：max-poll-records 由 500 提到 4000 后
            // 单批从约 2ms 变成约 20ms，窗口放大一个量级，因此这条从「偶发」变成「常见」
            // （hotpath-findings.md H14）
            throw e;
        } catch (Throwable t) {
            rethrowIfJvmError(t);
            consecutiveFailures++;
            log.error("本批处理失败，回退重放: topic={} count={} 连续失败={}",
                      topic(), records.count(), consecutiveFailures, t);
            rewindToBatchStart(records);
            backoff();
        }
    }

    /**
     * JVM 级错误（OOM、StackOverflow 等）原样放行，不得被当作一次批处理失败吞掉。
     */
    private static void rethrowIfJvmError(Throwable t) {
        if (t instanceof Error error) {
            throw error;
        }
    }

    /**
     * <b>解码在 poll 线程上顺序执行，不并行化</b>（engine-runtime-io.md §2.2）。
     *
     * <p>{@code messageCodec.decode} 是纯 CPU —— {@code cn-iot-message} 是零依赖叶子模块，
     * 模块边界契约保证它不可能含 I/O。而虚拟线程的全部价值在于阻塞时释放载体线程：
     * 纯 CPU 任务的真实并行度恒等于载体线程数，为每条记录建一个虚拟线程只会多出
     * 线程创建、{@code Future} 分配、并发队列 CAS 三份开销，与单条解码耗时（1~5µs）同量级。
     *
     * <p>量级对照：500 条顺序解码约 1.5ms，而同批时序写入属于主 IO 阶段。
     */
    private void processBatch(ConsumerRecords<String, byte[]> records) {
        long startedAt = System.nanoTime();
        List<DecodedRecord> decoded = new ArrayList<>(records.count());
        List<DlqPublisher.DeadLetter> deadLetters = new ArrayList<>();

        for (ConsumerRecord<String, byte[]> record : records) {
            decode(record, decoded, deadLetters);
        }

        // 先留证再落库：两者都要在 commit 之前完成，DLQ 失败时整批重放也不会丢证据。
        // 批量投递而非逐条 send().get()：整批毒消息时后者会退化成 N 次串行往返
        dlqPublisher.publishAll(deadLetters);
        handleBatch(decoded);

        if (metrics != null) {
            metrics.ingressReceived(topic(), records.count());
            if (!deadLetters.isEmpty()) {
                // 解码期死信按 reason 分组计数；reason 取自 DlqPublisher 的常量，值域有界
                Map<String, Integer> byReason = new HashMap<>();
                for (DlqPublisher.DeadLetter dl : deadLetters) {
                    byReason.merge(dl.reason(), 1, Integer::sum);
                }
                byReason.forEach((reason, n) -> metrics.ingressDlq(topic(), reason, n));
            }
            // 放在最后：本批的全部工作（含时序落库）都已完成，
            // 这才是 R10.1 要与 max.poll.interval.ms 比余量的那个耗时
            metrics.ingressBatchMillis(topic(), (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private void decode(ConsumerRecord<String, byte[]> record,
                        List<DecodedRecord> decoded,
                        List<DlqPublisher.DeadLetter> deadLetters) {
        String messageType = header(record, HEADER_MESSAGE_TYPE);
        if (messageType == null) {
            deadLetters.add(new DlqPublisher.DeadLetter(record, REASON_MISSING_TYPE_HEADER));
            return;
        }
        if (record.value() == null || record.value().length > maxMessageBytes()) {
            deadLetters.add(new DlqPublisher.DeadLetter(record, REASON_PAYLOAD_TOO_LARGE));
            return;
        }
        try {
            IotMessage message = messageCodec.decode(messageType, record.value());
            decoded.add(new DecodedRecord(record, message));
        } catch (MessageDecodeException e) {
            log.warn("消息解码失败: mt={} partition={} offset={} detail={}",
                     messageType, record.partition(), record.offset(), e.getMessage());
            deadLetters.add(new DlqPublisher.DeadLetter(record, e.getClass().getSimpleName()));
        }
    }

    /**
     * 可重试故障后的退避，线性增长并封顶。
     *
     * <p>退避<b>不是</b>放弃的计时器：这里只会遇到基础设施故障，正确行为是一直重试到下游恢复。
     * 退避只是避免空转打满 CPU 和日志。分区因此会停住并持续告警 ——
     * 停住可观测可恢复，把好数据丢进 DLQ 不可观测且需人工比对。
     */
    private void backoff() {
        long delay = Math.min(
            props.retryBackoffMs() * Math.max(1, consecutiveFailures),
            props.maxRetryBackoffMs());
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /**
     * 回退到本批每个分区的起始 offset，使整批重新投递。
     */
    private void rewindToBatchStart(ConsumerRecords<String, byte[]> records) {
        Map<TopicPartition, Long> earliest = new HashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            earliest.merge(new TopicPartition(record.topic(), record.partition()), record.offset(), Math::min);
        }
        earliest.forEach((partition, offset) -> {
            try {
                consumer.seek(partition, offset);
            } catch (Exception e) {
                log.error("回退 offset 失败: partition={} offset={}", partition, offset, e);
            }
        });
    }

    private static String header(ConsumerRecord<String, byte[]> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        // 等 poll 线程真正退出再返回：stop() 一返回 Vert.x 就继续 close()，
        // 拆掉它自己创建的 Redis/MySQL 传输层，而本轮消息的落库还要用它们
        // （vertx-pool-thread-affinity.md §3.4bis）。静默期与其余 verticle 统一取值
        VerticleQuiesce.join(getClass().getSimpleName() + " poll 线程", pollThread);
        log.info("{} 已停止", getClass().getSimpleName());
    }

    private void closeQuietly() {
        try {
            consumer.close();
        } catch (Exception e) {
            log.warn("关闭 Kafka consumer 失败", e);
        }
        try {
            dlqPublisher.close();
        } catch (Exception e) {
            log.warn("关闭 DLQ publisher 失败", e);
        }
        try {
            if (producer != null) {
                producer.close(Duration.ofSeconds(10));
            }
        } catch (Exception e) {
            log.warn("关闭 Kafka producer 失败", e);
        }
    }

    /**
     * 一条已解码的记录。保留原始 record 才能在业务判定失败时把它单独投 DLQ。
     */
    protected record DecodedRecord(ConsumerRecord<String, byte[]> record, IotMessage message) {
    }
}

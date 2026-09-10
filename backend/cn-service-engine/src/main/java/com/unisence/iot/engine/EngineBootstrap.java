package com.unisence.iot.engine;

import com.unisence.iot.common.crypto.AesGcmCipher;
import com.unisence.iot.engine.config.*;
import com.unisence.iot.engine.metadata.MetadataSyncVerticle;
import com.unisence.iot.engine.metadata.VertxPoolSocketLedger;
import com.unisence.iot.engine.metrics.EngineMetrics;
import com.unisence.iot.engine.online.*;
import com.unisence.iot.engine.repository.EngineMySQLRepository;
import com.unisence.iot.engine.route.RouteJsonEncoder;
import com.unisence.iot.engine.route.RouteMetrics;
import com.unisence.iot.engine.storage.EventLogWriter;
import com.unisence.iot.engine.storage.EventSchemaSync;
import com.unisence.iot.engine.storage.OnlineLogWriter;
import com.unisence.iot.engine.storage.PropertyLogWriter;
import com.unisence.iot.engine.verticle.DeviceAdmissionFilter;
import com.unisence.iot.engine.verticle.EventIngestionVerticle;
import com.unisence.iot.engine.verticle.PropertyIngestionVerticle;
import com.unisence.iot.message.codec.MessagePackMessageCodec;
import com.unisence.iot.metadata.*;
import com.unisence.iot.redis.RedisCommands;
import com.unisence.iot.redis.RedisPipeline;
import com.unisence.iot.rule.compiler.RuleScriptCompiler;
import com.unisence.iot.timeseries.TimeSeriesClient;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.sqlclient.Pool;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * {@code cn-service-engine} 进程入口。
 *
 * <p>本服务<b>不暴露任何 HTTP 端点</b>（module-boundary.md）：只消费 Kafka、执行规则、读写时序库/MySQL。
 *
 * <p>Verticle 一律以 {@link ThreadingModel#VIRTUAL_THREAD} 部署，配合同步阻塞写法；
 * 不使用 {@code vertx.createVirtualThreadWorkerExecutor()} —— 该方法不在 Vert.x 5 公共 API 中
 * （architecture-guide.md 轨道一第 2 条）。
 *
 * <p><b>配置校验发生在装配之前</b>：属性 record 的紧凑构造器承担全部不变量，任何越界都在这里抛出。
 *
 * <p><b>启动顺序是安全约束而非风格偏好</b>（metadata-sync-bus.md §八）：
 * 必须先完成元数据全量一致性引导、并追平启动期间产生的新水位，<b>之后</b>才部署 Kafka 消费 verticle。
 * 顺序反过来，第一批消息会撞上空快照而全部被判「产品未定义」进 DLQ。
 */
@Slf4j
public final class EngineBootstrap {

    private EngineBootstrap() {
    }

    public static void main(String[] args) {
        // 池大小必须在建 Vertx 之前读到，故此处先加载一次配置；
        // 后续 EngineConfigLoader.load(vertx) 仍会正常加载完整配置（幂等，只多一次文件读）
        Vertx vertx = Vertx.builder()
            .with(new io.vertx.core.VertxOptions()
                      .setWorkerPoolSize(EngineConfigLoader.bootstrapVertxWorkerPoolSize())
                      .setInternalBlockingPoolSize(EngineConfigLoader.bootstrapVertxInternalBlockingPoolSize()))
            .build();
        TimeSeriesClient timeSeriesClient = null;
        Pool mysqlPool = null;
        PropertyLogWriter propertyLogWriter = null;
        OnlineLogWriter onlineLogWriter = null;
        EventLogWriter eventLogWriter = null;
        EventSchemaSync eventSchemaSync = null;
        EngineMetrics metrics = null;
        Redis redis = null;
        DeviceOnlineStateService onlineStateService = null;
        RuleScriptCompiler ruleCompiler = null;
        // 元数据链路的唯一工作线程。单飞语义本就串行，这里要的不是并发而是「线程稳定」：
        // Vert.x SQL 连接池只复用 event loop 与调用方相同的连接，而调用方的 event loop 由
        // 线程 ID 取模决定。一次性虚拟线程每次 ID 都不同 → 复用永远命不中 → 连接池为每个
        // event loop 各建一条连接并随空闲淘汰反复重建
        // （metadata-sync-mysql-connection-exhaustion-incident.md §三）
        ExecutorService metadataWorker = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofVirtual().name("metadata-worker").unstarted(runnable));
        try {
            EngineConfigLoader config = EngineConfigLoader.load(vertx);
            EngineIngressProperties ingress = config.ingress();
            EngineRouteProperties route = config.route();
            route.requireForbidden(ingress.rawDataTopic(), ingress.eventTopic(), ingress.dlqTopic());
            EngineTimeSeriesProperties timeSeries = config.timeSeries();
            EngineMySQLProperties mysql = config.mysql();
            EngineRedisProperties redisProps = config.redis();
            EngineOnlineProperties online = config.online();
            MetadataProperties metadata = config.metadata();
            requireRedisBlockingHeadroom(online, redisProps);
            // 指标经 JMX 暴露（本模块禁止 HTTP 端点），与 rule-stream 的 RuleMetrics 同一采集通道。
            // 装配在最前：后续各组件按需注入，缺失时它们的行为完全不变（observability.md）
            metrics = new EngineMetrics();

            timeSeriesClient = EngineClients.timeSeries(timeSeries);
            timeSeriesClient.observer(metrics::timeSeriesFlush);
            mysqlPool = EngineClients.mysqlPool(vertx, mysql);
            propertyLogWriter = new PropertyLogWriter(timeSeriesClient, timeSeries, metrics);
            onlineLogWriter = new OnlineLogWriter(timeSeriesClient, timeSeries);
            eventLogWriter = new EventLogWriter(timeSeriesClient, timeSeries);
            eventSchemaSync = new EventSchemaSync(timeSeriesClient, timeSeries);

            // MySQL 必须可用；Redis 失败只记 DEGRADED 但不阻止引导 ——
            // Redis 在元数据链路上只承担「发现延迟」，不承担正确性（§0.3）
            redis = tryRedis(vertx, redisProps);
            boolean redisAvailable = redis != null;
            // 全模块共用一个分片器：单次 batch() 的命令数必须封顶，
            // 否则一个 poll 批的全部命令会挤进一次往返并击穿 max-waiting-handlers
            // 全仓唯一 Redis 入口：ErrorType 继承自 Throwable，业务类直接持有 Redis 时
            // 每个 catch(Exception) 都是无效防护（engine-runtime-io.md §5.1）
            RedisCommands redisCommands = new RedisCommands(redis, redisProps.pipelineBatchSize(), metrics);
            RedisPipeline redisPipeline = new RedisPipeline(redisCommands);

            // 与 admin 的 app.rule.script.* 同键空间；不一致时以本侧为准
            ruleCompiler = new RuleScriptCompiler(config.ruleScript());
            MetadataControlRepository controlRepository = new MetadataControlRepository(mysqlPool);
            MetadataSyncService syncService = new MetadataSyncService(
                controlRepository,
                new MetadataSnapshotBuilder(ruleCompiler),
                new ProductMetadataLoader(online.defaultTtlSeconds(), metadata),
                new DeviceMetadataLoader(metadata),
                new ThingModelMetadataLoader(metadata),
                new RuleMetadataLoader(metadata),
                metadata,
                metadataWorker,
                KafkaOutputTopicPolicyValidator.forRoutes(route.allowedTopicPrefixes(), route.forbiddenTopics()));

            DeviceMetadataCache deviceCache = new DeviceMetadataCache(
                new DeviceL2Store(redisCommands, redisPipeline, redisAvailable),
                new MySQLDeviceProjectionRepository(mysqlPool, metadata),
                syncService,
                metadata);
            // 元数据侧观测：经接口注入，避免 cn-metadata-sync 反向依赖 engine
            final EngineMetrics metricsObserver = metrics;
            final EventSchemaSync schemaSync = eventSchemaSync;
            final MetadataSyncService sync = syncService;
            syncService.observer(new com.unisence.iot.metadata.MetadataObserver() {
                @Override
                public void metadataConverge(String mode, long millis, int scopes) {
                    metricsObserver.metadataConverge(mode, millis, scopes);
                    schemaSync.sync(sync.current());
                }

                @Override
                public void metadataCatalogEntries(long count) {
                    metricsObserver.metadataCatalogEntries(count);
                }

                @Override
                public void metadataDbFallback(int batchSize, long millis) {
                    metricsObserver.metadataDbFallback(batchSize, millis);
                }
            });
            deviceCache.observer(metrics);
            UnknownDeviceRepairCoordinator unknownDeviceRepair = new UnknownDeviceRepairCoordinator(
                syncService, controlRepository, deviceCache, metadata);
            var crypto = config.fieldCrypto();
            DeviceCreateRepository deviceCreateRepository = new DeviceCreateRepository(
                mysqlPool, new AesGcmCipher(crypto.currentVersion(), crypto.keys()));

            // 引导失败即抛出：调用栈会走到下面的 catch，进程退出，绝不带着空快照消费
            bootstrapMetadata(syncService, metadataWorker);

            MetadataInstanceReporter reporter = new MetadataInstanceReporter(redisCommands, syncService, metadata);
            EngineMySQLRepository mysqlRepository = new EngineMySQLRepository(mysqlPool);
            DeviceLeaseStore leaseStore = new DeviceLeaseStore(redisCommands, redisPipeline);
            onlineStateService = new DeviceOnlineStateService(leaseStore, syncService, online, metrics);

            MessagePackMessageCodec codec = new MessagePackMessageCodec();
            RouteJsonEncoder jsonEncoder = new RouteJsonEncoder();
            RouteMetrics routeMetrics = new RouteMetrics(metrics.registry());
            DeploymentOptions options = new DeploymentOptions()
                .setThreadingModel(ThreadingModel.VIRTUAL_THREAD);
            // 按部署序记下 deploymentId：停机时要按<b>逆序</b>逐个 undeploy 并等待。
            // Vert.x 没有「先 undeploy 再关资源」的入口，只能自己拿着 ID 来做（§3.4ter）
            List<String> deployments = new ArrayList<>(5);

            // 触发源先于消费启动：引导期间 admin 可能又提交了新水位，
            // 让收敛器先跑起来才能在开始消费前追平（§八最后两步）
            MetadataHintStore metadataHintStore = new MetadataHintStore(redisCommands);
            deployments.add(deploy(vertx, options, new MetadataSyncVerticle(
                syncService, controlRepository, metadataHintStore, reporter,
                deviceCache, metadata,
                socketLedger(mysqlPool, mysql, redisProps, redisAvailable),
                redisAvailable)));
            catchUpBeforeConsuming(syncService, controlRepository, metadataWorker);

            // 设备存在性准入：两条链路共用一份判据，避免「同一条报文在不同链路上命运相反」
            // （metadata-sync-bus.md §6.7bis）
            DeviceAdmissionFilter admissionFilter =
                new DeviceAdmissionFilter(deviceCache, unknownDeviceRepair, syncService);

            // 两条链路独立消费：iot.raw.data 是高频洪流、iot.event 低频但要快，
            // 合用一个 poll 循环会让遥测积压把事件顶在队尾。
            //
            // 每条链路再按 consumer-count 部署多个消费者：它们同属一个消费组，
            // 由 Kafka 把分区分给各自的 poll 循环。此前每条链路只有 1 个消费者，
            // 48 个分区全由一条 poll 线程串行「拉批 → 写时序库 → 等写完 → 提交」，
            // 下游任一次停顿都让全部分区一起停（application.yml consumer-count 处有实测依据）。
            // 消费者之间无共享状态，各自提交自己被分到的分区 offset。
            int consumerCount = ingress.consumerCount();
            for (int i = 0; i < consumerCount; i++) {
                deployments.add(deploy(vertx, options, new PropertyIngestionVerticle(
                    ingress, codec, metrics, propertyLogWriter, syncService, onlineStateService,
                    admissionFilter, i, route, routeMetrics, jsonEncoder)));
                deployments.add(deploy(vertx, options, new EventIngestionVerticle(
                    ingress, codec, metrics, eventLogWriter, syncService, onlineStateService,
                    deviceCreateRepository, deviceCache, unknownDeviceRepair, metadataHintStore,
                    admissionFilter, i, route, routeMetrics, jsonEncoder)));
            }

            // 判活扫描与 transition 落库共用同一套 rendezvous 归属；实例身份复用元数据总线的 instanceId，
            // 两份成员视图迟早会不一致
            ShardOwnership ownership = new ShardOwnership(redisCommands, reporter.instanceId());
            deployments.add(deploy(vertx, options, new OnlineScanVerticle(
                leaseStore, ownership, online, reporter.instanceId(), metrics)));

            // 跳变落库：MySQL 与时序库都成功才 XACK。它取代了原先两个互不协调的内存累积器 ——
            // 后者无法判定何时可以确认，进程退出就会丢项（device-online-state-design.md §10.6）
            deployments.add(deploy(vertx, options, new OnlineTransitionVerticle(
                new OnlineTransitionStore(redisCommands), leaseStore, ownership, syncService, deviceCache,
                unknownDeviceRepair, mysqlRepository, onlineLogWriter, online, reporter.instanceId(), metrics)));

            log.info("cn-service-engine 启动完成: appliedHead={} redis={}",
                     syncService.appliedHead(), redisAvailable ? "可用" : "降级");

            final TimeSeriesClient timeSeriesRef = timeSeriesClient;
            final Pool mysqlRef = mysqlPool;
            final Redis redisRef = redis;
            final PropertyLogWriter writerRef = propertyLogWriter;
            final OnlineLogWriter logRef = onlineLogWriter;
            final EventLogWriter eventLogRef = eventLogWriter;
            final EventSchemaSync schemaSyncRef = eventSchemaSync;
            final DeviceOnlineStateService serviceRef = onlineStateService;
            final RuleScriptCompiler compilerRef = ruleCompiler;
            final EngineMetrics metricsRef = metrics;
            final int shutdownTimeoutMs = config.shutdownTimeoutMs();
            final List<String> deploymentIds = List.copyOf(deployments);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    log.info("收到停机信号，正在关闭 cn-service-engine...");
                    // 顺序是硬约束（契约 §3.4 / §3.4ter），三步缺一不可：
                    // 1. 显式 undeploy 并等待 —— 此时 Redis/MySQL 仍然活着，verticle 的 stop()
                    //    还要用它们（摘除实例状态、排空在途分片）。
                    //    **不能靠 vertx.close() 代劳**：它内部是「先 closeFuture.close() 关掉
                    //    自己创建的 NetClient（Redis/MySQL 客户端就建在其上），再 undeployAll()」，
                    //    顺序与这里需要的正好相反 —— 实测 stop() 里的每一次 Redis 调用都在
                    //    获取连接时就报 CONNECTION_CLOSED
                    awaitUndeployAll(vertx, deploymentIds, shutdownTimeoutMs);
                    // 2. 再关业务线程池与连接池
                    closeAll(metadataWorker, writerRef, logRef, eventLogRef, schemaSyncRef, serviceRef, compilerRef,
                             timeSeriesRef, mysqlRef, redisRef);
                    // 指标最后关：上面每个组件的关闭路径都可能还在记录
                    close("engine 指标", metricsRef);
                    // 3. 最后关 Vert.x 本体（此时已无 verticle，undeployAll 是空操作）
                    awaitVertxClose(vertx, shutdownTimeoutMs);
                } finally {
                    // 日志必须最后关，且必须由这里关：log4j2-*.xml 已 shutdownHook="disable"，
                    // 否则 Log4j2 自带的钩子会与本钩子并发竞争并抢先关掉日志，
                    // 上面每一条停机记录都变成 "Ignoring log event after log4j was shut down"
                    // 而被丢弃 —— 日志文件干净得和停机干净一模一样（logging-runtime.md §6）
                    LogManager.shutdown();
                }
            }, "engine-shutdown"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeAll(metadataWorker,
                     propertyLogWriter,
                     onlineLogWriter,
                     eventLogWriter,
                     eventSchemaSync,
                     onlineStateService,
                     ruleCompiler, timeSeriesClient, mysqlPool, redis);
            close("engine 指标", metrics);
            vertx.close();
            throw new IllegalStateException("cn-service-engine 启动被中断", e);
        } catch (Exception e) {
            log.error("cn-service-engine 启动失败", e);
            closeAll(metadataWorker,
                     propertyLogWriter,
                     onlineLogWriter,
                     eventLogWriter,
                     eventSchemaSync,
                     onlineStateService,
                     ruleCompiler, timeSeriesClient, mysqlPool, redis);
            close("engine 指标", metrics);
            vertx.close();
            throw new IllegalStateException("cn-service-engine 启动失败", e);
        }
    }

    /**
     * 装配连接账本观测：MySQL 与 Redis 共用同一套 Vert.x 连接池与同一条 event loop 亲和性判据，
     * 故障模式相同，一次 {@code /proc} 扫描同时给出两者读数
     * （metadata-sync-mysql-connection-exhaustion-incident.md §四bis.4）。
     *
     * <p>Redis 目标没有账本读数：{@code io.vertx.redis.client.Redis} 未暴露任何池大小访问器。
     * 这不影响告警 —— 判据取的是配置上限而非账本。
     */
    private static VertxPoolSocketLedger socketLedger(Pool mysqlPool,
                                                      EngineMySQLProperties mysql,
                                                      EngineRedisProperties redisProps,
                                                      boolean redisAvailable) {
        List<VertxPoolSocketLedger.Target> targets = new ArrayList<>(2);
        targets.add(new VertxPoolSocketLedger.Target(
            "MySQL", mysql.port(), mysql.poolMaxSize(), mysqlPool::size));
        if (redisAvailable) {
            targets.add(new VertxPoolSocketLedger.Target(
                "Redis", redisPort(redisProps.connectionString()), redisProps.poolMaxSize(), null));
        }
        return new VertxPoolSocketLedger(targets);
    }

    /**
     * 从 {@code redis://host:port} / {@code rediss://host:port} 取端口，未显式指定时用 6379。
     */
    private static int redisPort(String connectionString) {
        try {
            int port = URI.create(connectionString).getPort();
            return port > 0 ? port : DEFAULT_REDIS_PORT;
        } catch (IllegalArgumentException e) {
            log.warn("无法从 Redis 连接串解析端口，账本观测按默认端口 {} 统计: connectionString={}",
                     DEFAULT_REDIS_PORT, connectionString, e);
            return DEFAULT_REDIS_PORT;
        }
    }

    private static final int DEFAULT_REDIS_PORT = 6379;

    /**
     * Redis 不可用时降级而非失败：MySQL 反熵仍能保证元数据正确收敛，只是发现延迟上升。
     */
    private static Redis tryRedis(Vertx vertx, EngineRedisProperties props) {
        try {
            return EngineClients.redis(vertx, props);
        } catch (Exception e) {
            log.error("Redis 客户端建立失败，元数据总线降级为仅 MySQL 反熵", e);
            return null;
        }
    }

    /**
     * 在元数据工作线程上完成全量一致性引导。
     *
     * <p>{@code vertx-mysql-client} 返回 {@code Future}，配 {@code await()} 写成顺序代码；
     * {@code await()} 会阻塞当前线程，因此必须放在虚拟线程上。
     *
     * <p>用的是<b>与后续收敛同一条</b>工作线程而不是临时线程：引导期建立的连接会长期留在池里，
     * 若绑在一次性线程的 event loop 上，后续收敛永远复用不到它，只会再建一条。
     */
    private static void bootstrapMetadata(MetadataSyncService syncService, ExecutorService worker)
        throws Exception {
        try {
            worker.submit(syncService::bootstrapOrThrow).get();
        } catch (ExecutionException e) {
            Throwable error = e.getCause();
            log.error("元数据全量引导失败，进程不会开始消费", error);
            if (error instanceof Exception exception) {
                throw exception;
            }
            if (error instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("元数据全量引导失败", error);
        }
        if (syncService.current() == null) {
            throw new IllegalStateException("元数据全量引导未产出根快照，拒绝启动消费链路");
        }
    }

    /**
     * 消费前再追一次水位。
     *
     * <p>引导读的是某个时点的一致性快照；从那一刻到现在，admin 完全可能又提交了几次。
     * 不追平就直接消费，第一批消息看到的就是启动瞬间的旧代际。
     */
    private static void catchUpBeforeConsuming(MetadataSyncService syncService,
                                               MetadataControlRepository repository,
                                               ExecutorService worker) throws Exception {
        long committedHead;
        try {
            committedHead = worker.submit(repository::readCommittedHead).get();
        } catch (ExecutionException e) {
            // 追平失败不阻止启动：已有完整 LKG 可以消费，反熵会继续推进
            log.error("启动期读取提交水位失败，将以当前快照开始消费", e.getCause());
            return;
        }
        if (committedHead <= syncService.appliedHead()) {
            return;
        }
        log.info("启动期间水位已推进，先追平再消费: applied={} committed={}",
                 syncService.appliedHead(), committedHead);
        syncService.observeDesiredHead(committedHead, MetadataSyncTrigger.STARTUP);

        // 等待<b>必须留在调用线程</b>：收敛任务本身排在 worker 的队列上，
        // 在 worker 里等它就是单线程自等死锁 —— 只会空转到 30s 超时后带着旧代际开始消费
        long deadline = System.currentTimeMillis() + 30_000;
        while (syncService.appliedHead() < committedHead && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private static String deploy(Vertx vertx, DeploymentOptions options, io.vertx.core.Verticle verticle)
        throws Exception {
        return vertx.deployVerticle(verticle, options).toCompletionStage().toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
    }

    /**
     * 关闭顺序：先停元数据工作线程与攒批器（把在途跳变刷出去），再关写入器与连接池。
     *
     * <p>元数据工作线程排在最前：它持有 MySQL 连接做收敛，晚于连接池关闭会在停机日志里
     * 刷出一片「池已关闭」的伪故障，掩盖真正的停机问题。
     */
    /**
     * 阻塞等待 {@code vertx.close()} 完成，即等到全部 verticle 的 {@code stop()} 跑完。
     *
     * <p>停机钩子跑在普通平台线程上，不能用 {@code Future.await()}（那需要虚拟线程 context），
     * 因此走 {@code CompletableFuture#get}。
     *
     * <p>超时或失败都只记日志、不抛出：调用方随后仍要关闭连接池，
     * 让停机在这里中断只会把「关得不干净」变成「基本没关」。
     */
    /**
     * 逆部署序逐个 undeploy 并等待，全程共用一份停机预算。
     *
     * <p><b>为什么不能直接 {@code vertx.close()}</b>：{@code VertxImpl.close()} 的第一步是
     * {@code closeFuture.close()} —— 关掉 Vert.x 自己创建的全部资源，其中就包括
     * {@code Redis.createClient(vertx, …)} 与 {@code Pool.pool(vertx, …)} 底下的
     * {@code NetClient}；{@code deploymentManager.undeployAll()} 排在它<b>之后</b>。
     * 于是 verticle 的 {@code stop()} 一开始跑，Redis/MySQL 就已经没了，
     * 每一次调用都在「获取连接」这一步报 {@code CONNECTION_CLOSED}
     * （2026-08-07 实测：249 个 transition 分片在停机后 6ms 内全部如此失败）。
     *
     * <p><b>为什么逆序而不是并发</b>：停机也有依赖方向。消费入口必须先停，
     * 元数据触发源最后停 —— 它的 {@code stop()} 要摘除实例状态，摘不掉就得等 TTL 自然过期，
     * 期间 admin 视图里挂着一个已经死掉的实例。
     */
    private static void awaitUndeployAll(Vertx vertx, List<String> deploymentIds, int timeoutMs) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (int i = deploymentIds.size() - 1; i >= 0; i--) {
            String deploymentId = deploymentIds.get(i);
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                log.error("停机预算已耗尽，尚有 {} 个 verticle 未停止: timeoutMs={}，"
                          + "接下来关闭连接池会让它们的在途请求失败", i + 1, timeoutMs);
                return;
            }
            try {
                vertx.undeploy(deploymentId).toCompletionStage().toCompletableFuture()
                    .get(remainingMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待 verticle 停止被中断，停机继续: deploymentId={}", deploymentId);
                return;
            } catch (TimeoutException e) {
                // 不降级为 warn：这条记录是后面那批「连接已关闭」伪故障的因果起点
                log.error("等待 verticle 停止超时: deploymentId={} 剩余预算={}ms，"
                          + "接下来关闭连接池可能产生在途任务失败", deploymentId, remainingMs, e);
            } catch (ExecutionException e) {
                log.error("停止 verticle 失败: deploymentId={}", deploymentId, e.getCause());
            }
        }
    }

    /**
     * 关闭 Vert.x 本体。调用时 verticle 已全部 undeploy、连接池已全部关闭，
     * 这里只剩 event loop 与 worker 池。
     */
    private static void awaitVertxClose(Vertx vertx, int timeoutMs) {
        try {
            vertx.close().toCompletionStage().toCompletableFuture()
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 Vert.x 关闭被中断");
        } catch (TimeoutException e) {
            log.error("等待 Vert.x 关闭超时: timeoutMs={}", timeoutMs, e);
        } catch (ExecutionException e) {
            log.error("关闭 Vert.x 失败", e.getCause());
        }
    }

    /**
     * 跨配置块校验：transition 排空的<b>阻塞读</b>不得吃光 Redis 连接池。
     *
     * <p><b>为什么必须在启动期堵死</b>：`OnlineTransitionVerticle` 的每个分组都常驻一条
     * {@code XREADGROUP BLOCK} 连接（实测 {@code redis> info clients} 的 {@code blocked_clients}
     * 恒等于 {@code transition-drain-shard-concurrency}）。一旦它 ≥ {@code pool-max-size}，
     * <b>池里每一条连接都被阻塞读占住</b>，续租、在线扫描、元数据回源将全部拿不到连接 ——
     * 而表征是各处超时，完全不指向成因。这是功能性错误（自死锁），不是调优问题。
     *
     * <p>校验放在这里而不是任一 record 的构造器里：两个值分属
     * {@code app.engine.online.*} 与 {@code app.engine.redis.*}，构造器互相看不见
     * —— 这正是本项缺陷此前被漏掉的原因（`hotpath-findings.md` H11）。
     *
     * <p><b>只堵死死锁条件，不规定余量</b>：剩余连接数够不够是容量问题，取决于续租 QPS
     * 与扫描并发，属 R10.1 待压测定值项，故此处仅在启动日志里把余量报出来供观测。
     */
    // 【已移除】warnIfCatalogExceedsReconcileBudget —— A5 的交叉约束已由根因消除。
    //
    // 该校验存在的前提是「未知设备修复要等全量重建完成」，因此目录上限与修复超时之间存在耦合。
    // 2026-08-09 把修复路径改为「head 探测 + MySQL 权威回源」后（metadata-sync-bus.md §6.8 修订），
    // 修复不再等待收敛，两个配置**彻底解耦** —— 校验的前提消失，故一并删除，
    // 而不是留一个永远不会触发的检查制造「已经防住了」的错觉。
    private static void requireRedisBlockingHeadroom(EngineOnlineProperties online,
                                                     EngineRedisProperties redis) {
        int blocking = online.transitionDrainShardConcurrency();
        int poolSize = redis.poolMaxSize();
        if (blocking >= poolSize) {
            throw new IllegalArgumentException(
                "app.engine.online.transition-drain-shard-concurrency(" + blocking
                    + ") 必须小于 app.engine.redis.pool-max-size(" + poolSize
                    + ")：每个排空分组常驻一条 XREADGROUP BLOCK 连接，"
                    + "取满会让续租、在线扫描与元数据回源永久拿不到连接");
        }
        log.info("Redis 连接预算: 池={} 常驻阻塞读={} 可用于非阻塞命令={}",
                 poolSize, blocking, poolSize - blocking);
    }

    private static void closeAll(ExecutorService metadataWorker,
                                 PropertyLogWriter propertyWriter, OnlineLogWriter onlineWriter,
                                 EventLogWriter eventWriter, EventSchemaSync eventSchemaSync,
                                 DeviceOnlineStateService stateService,
                                 RuleScriptCompiler compiler,
                                 TimeSeriesClient timeSeriesClient, Pool mysqlPool, Redis redis) {
        close("元数据工作线程", metadataWorker == null ? null : metadataWorker::shutdownNow);
        close("在线状态攒批器", stateService);
        close("属性写入器", propertyWriter);
        close("上下线日志写入器", onlineWriter);
        close("事件日志写入器", eventWriter);
        close("事件表反熵池", eventSchemaSync);
        close("规则脚本编译器", compiler);
        close("时序数据库客户端", timeSeriesClient);
        close("MySQL 连接池", mysqlPool == null ? null : (AutoCloseable) mysqlPool::close);
        close("Redis 客户端", redis == null ? null : (AutoCloseable) redis::close);
    }

    private static void close(String what, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("关闭{}失败", what, e);
        }
    }
}

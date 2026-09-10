package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.metadata.MetadataScope;
import com.unisence.iot.rule.sdk.ThingModelSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 元数据收敛器：整个 engine 唯一的元数据写入口（metadata-sync-bus.md §七、§八）。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>所有触发源只能调用 {@link #observeDesiredHead}，它只做一件事 —— 抬高目标水位；</li>
 *   <li>同一实例任何时刻<b>最多一个</b>构建任务（single-flight）；</li>
 *   <li>每轮直接收敛到该一致性快照可见的 {@code committedHead}，<b>不逐事件回放</b>。
 *       这是「元数据当前态同步」而非事件溯源 —— 同一条规则被连改 20 次，只编译最新那版一次；</li>
 *   <li>只有候选根<b>完整构建成功</b>才推进 {@code appliedHead}，否则保留 LKG。</li>
 * </ul>
 */
@Slf4j
public final class MetadataSyncService {

    private final MetadataControlRepository repository;
    private final MetadataSnapshotBuilder snapshotBuilder;
    private final ProductMetadataLoader productLoader;
    private final DeviceMetadataLoader deviceLoader;
    private final ThingModelMetadataLoader thingModelLoader;
    private final RuleMetadataLoader ruleLoader;
    private final MetadataProperties properties;
    /**
     * 收敛任务的唯一执行者，由 owner 模块提供（本模块不做生命周期胶水）。
     *
     * <p><b>必须是一条长生命周期的线程</b>，不能是「每次任务新建一条」。Vert.x SQL 连接池
     * 的首选复用判据是 {@code SAME_EVENT_LOOP_SELECTOR} —— 只复用 event loop 与调用方相同的
     * 连接；而调用方的 event loop 由 {@code VertxImpl.stickyEventLoop()} 按线程 ID 取模决定。
     * 一次性线程的 ID 每次都不同，于是复用永远命不中，连接池被迫走「新建连接」分支，
     * 直到每个 event loop 各占一条连接。这正是 MySQL 连接耗尽故障的放大器
     * （metadata-sync-mysql-connection-exhaustion-incident.md §三）。
     */
    private final Executor reconcileExecutor;
    private final MetadataCandidateValidator candidateValidator;

    private final AtomicReference<EngineMetadataSnapshot> metadataRef = new AtomicReference<>();
    /**
     * 已观察到的最大目标水位，只增不减 —— 重复与乱序提示因此天然幂等。
     */
    private final AtomicLong desiredHead = new AtomicLong();
    /**
     * single-flight 标志；CAS 成功者负责调度唯一一次收敛。
     */
    private final AtomicBoolean reconcileScheduled = new AtomicBoolean();

    private volatile MetadataSyncState state = MetadataSyncState.BOOTSTRAPPING;
    private volatile long lastAttemptAt;
    private volatile long lastSuccessAt;
    private volatile long lastBuildDurationMs;
    /**
     * 可选观测回调；默认 NOOP，装配方按需注入（MetadataObserver 的模块边界说明）。
     */
    private volatile MetadataObserver observer = MetadataObserver.NOOP;
    /**
     * 本轮是否退化为全量重建；仅供观测上报，不参与任何判定。
     */
    private volatile boolean lastConvergeFull;
    private volatile int lastConvergeScopes;
    private volatile int consecutiveFailures;
    private volatile String lastErrorCode;

    public MetadataSyncService(MetadataControlRepository repository,
                               MetadataSnapshotBuilder snapshotBuilder,
                               ProductMetadataLoader productLoader,
                               DeviceMetadataLoader deviceLoader,
                               ThingModelMetadataLoader thingModelLoader,
                               RuleMetadataLoader ruleLoader,
                               MetadataProperties properties,
                               Executor reconcileExecutor,
                               MetadataCandidateValidator candidateValidator) {
        this.reconcileExecutor = reconcileExecutor;
        this.repository = repository;
        this.snapshotBuilder = snapshotBuilder;
        this.productLoader = productLoader;
        this.deviceLoader = deviceLoader;
        this.thingModelLoader = thingModelLoader;
        this.ruleLoader = ruleLoader;
        this.properties = properties;
        this.candidateValidator = candidateValidator == null
            ? MetadataCandidateValidator.NOOP
            : candidateValidator;
    }

    // ────────────────────────────── 启动引导 ──────────────────────────────

    /**
     * 全量一致性引导（§八）。
     *
     * <p><b>不恢复本地游标、不复用任何持久化状态</b>：本地对象与游标一旦不匹配（例如上次退出时
     * 构建到一半），后果是永久性的静默错误。每次启动都在一个数据库一致性快照内从零建立，
     * 代价可控且没有推理负担。
     *
     * @throws RuntimeException 引导失败；调用方必须据此<b>不部署</b>任何 Kafka 消费 verticle
     */
    public EngineMetadataSnapshot bootstrapOrThrow() {
        long started = System.nanoTime();
        state = MetadataSyncState.BOOTSTRAPPING;
        EngineMetadataSnapshot empty = emptySnapshot();

        LoadResult loaded = repository.inConsistentSnapshot(readView ->
                                                                loadDomains(readView, empty, fullDomainScopes()));

        EngineMetadataSnapshot root = snapshotBuilder.build(empty, loaded.patches(), loaded.committedHead());
        candidateValidator.validate(root);
        metadataRef.set(root);
        desiredHead.accumulateAndGet(loaded.committedHead(), Math::max);
        lastBuildDurationMs = (System.nanoTime() - started) / 1_000_000;
        lastSuccessAt = System.currentTimeMillis();
        lastAttemptAt = lastSuccessAt;
        state = MetadataSyncState.READY;
        log.info("元数据全量引导完成: appliedHead={} 产品数={} 设备目录={} 物模型数={} 规则数={} 耗时={}ms",
                 root.appliedHead(), root.productsByKey().size(), root.deviceCatalog().size(),
                 root.thingModelsByProductKey().size(), root.rules().size(), lastBuildDurationMs);
        return root;
    }

    /**
     * 引导起点：空根。设备 Bloom 取 permissive，避免空位图把真实设备判成不存在。
     */
    private EngineMetadataSnapshot emptySnapshot() {
        return new EngineMetadataSnapshot(
            0L, Map.of(), Map.of(),
            ShardedDeviceCatalog.empty(properties.deviceCatalogShardCount()),
            Map.of(), RuleSnapshot.empty());
    }

    // ────────────────────────────── 触发与单飞 ──────────────────────────────

    /**
     * 所有运行期触发的唯一入口。
     *
     * <p>只抬高目标水位并触发 single-flight。刻意不接受「刷新某个域」这类参数 ——
     * 有了旁路入口，「当前内存里是哪一代」就无法再从水位推断。
     */
    public void observeDesiredHead(long incomingHead, MetadataSyncTrigger trigger) {
        if (incomingHead < 0) {
            log.warn("忽略非法目标水位: incomingHead={} trigger={}", incomingHead, trigger);
            return;
        }
        long updated = desiredHead.accumulateAndGet(incomingHead, Math::max);
        EngineMetadataSnapshot current = metadataRef.get();
        if (current != null && updated <= current.appliedHead()) {
            return;
        }
        scheduleReconcile(trigger);
    }

    private void scheduleReconcile(MetadataSyncTrigger trigger) {
        if (!reconcileScheduled.compareAndSet(false, true)) {
            // 已有构建在途；它结束时会重新检查 desiredHead，不会漏掉本次提示
            return;
        }
        try {
            reconcileExecutor.execute(() -> {
                try {
                    coalesceThenReconcile(trigger);
                } catch (Exception e) {
                    log.error("元数据收敛任务异常退出: trigger={}", trigger, e);
                }
            });
        } catch (RuntimeException e) {
            // 提交失败（executor 已关闭等）必须把单飞标志放回去，
            // 否则本实例再也不会调度任何一轮收敛，且水位停滞不会有任何日志
            reconcileScheduled.set(false);
            log.error("提交元数据收敛任务失败: trigger={}", trigger, e);
        }
    }

    /**
     * 双限合并窗口。
     *
     * <p>首个提示等待 {@code coalesce-delay-ms}，把「一次业务操作产生的连续提交」合并成一轮构建。
     * 但持续变化不能无限推迟，因此 {@code max-coalesce-delay-ms} 是硬上限 ——
     * 少了它，一个持续写入的批量导入会让所有实例永远停在旧代际。
     */
    private void coalesceThenReconcile(MetadataSyncTrigger trigger) {
        long deadline = System.currentTimeMillis() + properties.maxCoalesceDelayMs();
        try {
            long previous;
            do {
                previous = desiredHead.get();
                Thread.sleep(properties.coalesceDelayMs());
            } while (desiredHead.get() != previous && System.currentTimeMillis() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reconcileScheduled.set(false);
            return;
        }
        reconcileLoop(trigger);
    }

    /**
     * 按 §7.3 收敛到数据库可见的 head。仅由 single-flight 调度器调用。
     */
    void reconcileLoop(MetadataSyncTrigger trigger) {
        try {
            while (true) {
                EngineMetadataSnapshot current = metadataRef.get();
                if (current == null) {
                    log.warn("尚未完成引导，跳过收敛: trigger={}", trigger);
                    return;
                }
                if (desiredHead.get() <= current.appliedHead()) {
                    state = MetadataSyncState.READY;
                    return;
                }
                state = MetadataSyncState.CONVERGING;
                if (!reconcileOnce(current, trigger)) {
                    return;
                }
            }
        } finally {
            // 顺序不能反：必须先清标志，再检查是否有更高水位。
            // 若先检查后清标志，两者之间到达的提示会看到标志仍为 true 而不调度，造成漏唤醒
            reconcileScheduled.set(false);
            EngineMetadataSnapshot current = metadataRef.get();
            if (current != null && desiredHead.get() > current.appliedHead()) {
                scheduleReconcile(MetadataSyncTrigger.RETRY);
            }
        }
    }

    /**
     * @return {@code true} 表示可以继续下一轮；{@code false} 表示本次已结束（成功追平或失败退避）
     */
    private boolean reconcileOnce(EngineMetadataSnapshot current, MetadataSyncTrigger trigger) {
        long started = System.nanoTime();
        lastAttemptAt = System.currentTimeMillis();
        try {
            LoadResult loaded = repository.inConsistentSnapshot(readView -> {
                long committedHead = readView.committedHead();
                if (committedHead <= current.appliedHead()) {
                    return null;
                }
                Map<MetaKeyEnum, Set<Long>> scopes = resolveScopes(readView, current.appliedHead(), committedHead);
                return loadDomains(readView, current, scopes);
            });
            if (loaded == null) {
                state = MetadataSyncState.READY;
                return false;
            }

            // 构建（含 Groovy 编译）严格发生在只读事务关闭之后
            EngineMetadataSnapshot candidate =
                snapshotBuilder.build(current, loaded.patches(), loaded.committedHead());
            candidateValidator.validate(candidate);
            metadataRef.set(candidate);

            lastBuildDurationMs = (System.nanoTime() - started) / 1_000_000;
            lastSuccessAt = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastErrorCode = null;
            log.info("元数据已收敛: appliedHead={}→{} 触发={} 耗时={}ms",
                     current.appliedHead(), candidate.appliedHead(), trigger, lastBuildDurationMs);
            observer.metadataConverge(lastConvergeFull ? MetadataObserver.MODE_FULL
                                          : MetadataObserver.MODE_INCREMENTAL,
                                      lastBuildDurationMs, lastConvergeScopes);
            return true;
        } catch (Exception e) {
            onFailure(e, trigger);
            return false;
        }
    }

    /**
     * 失败处理：保留 LKG，退避重试。
     *
     * <p>绝不安装空根或部分根 —— 用空数据覆盖会让所有消息因「产品未定义」全部进 DLQ，
     * 一次数据库抖动就能清空整条流。
     */
    private void onFailure(Exception e, MetadataSyncTrigger trigger) {
        consecutiveFailures++;
        if (consecutiveFailures >= properties.failureAlertThreshold()) {
            state = MetadataSyncState.DEGRADED;
        }
        long delay = Math.min(
            properties.retryMaxDelayMs(),
            properties.retryInitialDelayMs() * (1L << Math.min(consecutiveFailures - 1, 16)));
        if (e instanceof MetadataCandidateRejectedException rejected) {
            lastErrorCode = "APPLY_FAILED";
            log.error(
                "候选元数据被拒绝，保留 LKG 并退避重试: ruleKind={} ruleId={} outputId={} topic={} reason={} 触发={} 连续失败={} 退避={}ms",
                rejected.ruleKind(),
                rejected.ruleId(),
                rejected.outputId(),
                rejected.topic(),
                rejected.reason(),
                trigger,
                consecutiveFailures,
                delay,
                e);
        } else {
            lastErrorCode = e.getClass().getSimpleName();
            log.error("元数据收敛失败，保留 LKG 并退避重试: 触发={} 连续失败={} 退避={}ms",
                      trigger, consecutiveFailures, delay, e);
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        // 退避结束后由 finally 里的重调度接手，这里不直接递归，避免栈无限增长
        scheduleRetryAfterBackoff();
    }

    private void scheduleRetryAfterBackoff() {
        EngineMetadataSnapshot current = metadataRef.get();
        if (current != null && desiredHead.get() > current.appliedHead()) {
            try {
                reconcileExecutor.execute(() -> {
                    if (reconcileScheduled.compareAndSet(false, true)) {
                        try {
                            reconcileLoop(MetadataSyncTrigger.RETRY);
                        } catch (Exception e) {
                            log.error("元数据退避重试异常退出", e);
                        }
                    }
                });
            } catch (RuntimeException e) {
                log.error("提交元数据退避重试任务失败", e);
            }
        }
    }

    // ────────────────────────────── 范围解析与加载 ──────────────────────────────

    /**
     * 决定本轮要重查哪些聚合根。
     *
     * <p>三种情况会退化成全域重建，且都必须退化 —— 猜测缺失范围就是静默漏刷新：
     * <ol>
     *   <li>变更目录已被清理出缺口；</li>
     *   <li>去重后的 scope 数超过 {@code max-incremental-scopes}（此时增量已无收益）；</li>
     *   <li>目录里出现 {@code scope_id=0} 的强制重建标记。</li>
     * </ol>
     */
    /**
     * 装配期注入观测回调；未注入时所有回调为空操作。
     */
    public void observer(MetadataObserver observer) {
        this.observer = observer == null ? MetadataObserver.NOOP : observer;
    }

    private Map<MetaKeyEnum, Set<Long>> resolveScopes(MetadataReadView readView, long appliedHead, long committedHead) {
        Set<MetadataScope> scopes = repository.readChanges(readView, appliedHead, committedHead);
        if (scopes == null) {
            log.warn("变更目录存在缺口，转为全量一致性重建: appliedHead={} committedHead={}",
                     appliedHead, committedHead);
            lastConvergeFull = true;
            lastConvergeScopes = 0;
            return fullDomainScopes();
        }
        if (scopes.size() > properties.maxIncrementalScopes()) {
            log.info("去重后 scope 数超过增量上限，转为全量重建: scopes={} 上限={}",
                     scopes.size(), properties.maxIncrementalScopes());
            lastConvergeFull = true;
            lastConvergeScopes = scopes.size();
            return fullDomainScopes();
        }
        lastConvergeFull = false;
        lastConvergeScopes = scopes.size();
        Map<MetaKeyEnum, Set<Long>> byDomain = new EnumMap<>(MetaKeyEnum.class);
        for (MetadataScope scope : scopes) {
            byDomain.computeIfAbsent(scope.metaKey(), k -> new HashSet<>()).add(scope.scopeId());
        }
        // scope_id=0 与具体 ID 混在同一域时，全域重建覆盖一切，保留其余 ID 只是无谓的查询
        for (var entry : byDomain.entrySet()) {
            if (entry.getValue().contains(0L)) {
                entry.setValue(Set.of(0L));
                // 观测口径修正：含 scope_id=0 即该域走全量重建，耗时特征与「增量」完全不同。
                // 初版埋点只把两处早退分支记为 full，导致强制重建被误标为 incremental ——
                // 而 A5 要的正是全量重建的真实耗时，标错就答不了那个问题
                lastConvergeFull = true;
            }
        }
        return byDomain;
    }

    private Map<MetaKeyEnum, Set<Long>> fullDomainScopes() {
        Map<MetaKeyEnum, Set<Long>> all = new EnumMap<>(MetaKeyEnum.class);
        for (MetaKeyEnum metaKey : MetaKeyEnum.values()) {
            all.put(metaKey, Set.of(0L));
        }
        return all;
    }

    /**
     * 在同一读视图内按固定顺序加载各域。
     *
     * <p>顺序是<b>产品 → 设备版本目录 → 物模型 → 规则</b>：后三者都要用产品映射把
     * {@code product_id} 解析成 {@code productKey}，顺序颠倒会读到上一代产品。
     */
    private LoadResult loadDomains(MetadataReadView readView,
                                   EngineMetadataSnapshot current,
                                   Map<MetaKeyEnum, Set<Long>> scopes) {
        List<MetadataRawPatch> patches = new ArrayList<>(4);

        MetadataRawPatch productPatch = null;
        Set<Long> productScopes = scopes.get(MetaKeyEnum.IOT_PRODUCT);
        if (productScopes != null && !productScopes.isEmpty()) {
            productPatch = productLoader.load(productScopes, readView);
            patches.add(productPatch);
        }
        // 产品域构建完成后立刻发布候选映射，供设备与规则 loader 解析 productKey
        MetadataControlRepository.publishCandidateProducts(readView,
                                                           MetadataSnapshotBuilder.mergeProductsById(current.productsById(),
                                                                                                     productPatch));

        Set<Long> deviceScopes = scopes.get(MetaKeyEnum.IOT_DEVICE);
        if (deviceScopes != null && !deviceScopes.isEmpty()) {
            patches.add(deviceLoader.load(deviceScopes, readView));
        }
        Set<Long> thingModelScopes = scopes.get(MetaKeyEnum.IOT_THING_MODEL);
        if (thingModelScopes != null && !thingModelScopes.isEmpty()) {
            patches.add(thingModelLoader.load(thingModelScopes, readView));
        }
        Set<Long> ruleScopes = scopes.get(MetaKeyEnum.IOT_RULES);
        if (ruleScopes != null && !ruleScopes.isEmpty()) {
            patches.add(ruleLoader.load(ruleScopes, readView));
        }
        return new LoadResult(readView.committedHead(), patches);
    }

    private record LoadResult(long committedHead, List<MetadataRawPatch> patches) {
    }

    // ────────────────────────────── 读取接口 ──────────────────────────────

    /**
     * 当前根快照；引导完成前为 {@code null}。
     */
    public EngineMetadataSnapshot current() {
        return metadataRef.get();
    }

    public long desiredHead() {
        return desiredHead.get();
    }

    public long appliedHead() {
        EngineMetadataSnapshot current = metadataRef.get();
        return current == null ? 0L : current.appliedHead();
    }

    public MetadataSyncState state() {
        return state;
    }

    public long lastAttemptAt() {
        return lastAttemptAt;
    }

    public long lastSuccessAt() {
        return lastSuccessAt;
    }

    public long lastBuildDurationMs() {
        return lastBuildDurationMs;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    /**
     * 产品运行元数据；未定义该产品时返回 {@code null}，调用方须据此把消息投 DLQ。
     */
    public ProductRuntimeMeta product(String productKey) {
        EngineMetadataSnapshot current = metadataRef.get();
        return current == null ? null : current.product(productKey);
    }

    /**
     * 物模型快照；未定义时返回 {@code null}，语义同上。
     */
    public ThingModelSnapshot thingModel(String productKey) {
        EngineMetadataSnapshot current = metadataRef.get();
        return current == null ? null : current.thingModel(productKey);
    }
}

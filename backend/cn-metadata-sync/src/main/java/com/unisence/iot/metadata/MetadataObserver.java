package com.unisence.iot.metadata;

/**
 * 元数据同步总线的观测回调（observability.md「engine 指标」）。
 *
 * <p><b>为什么是接口</b>：本模块（{@code cn-metadata-sync}）不得依赖 {@code cn-service-engine}，
 * 否则形成反向依赖（module-boundary.md）。与 {@link com.unisence.iot.redis.RedisObserver}
 * 同一处理方式：这里只定义回调契约，由宿主模块实现。
 *
 * <p>实现方必须廉价且不抛异常 —— 它跑在收敛与设备解析路径上。
 */
public interface MetadataObserver {

    /**
     * 什么都不做的实现，避免调用点判空。
     */
    MetadataObserver NOOP = new MetadataObserver() {
    };

    String MODE_INCREMENTAL = "incremental";
    String MODE_FULL = "full";

    /**
     * 一轮收敛完成。
     *
     * <p><b>这是 A5 的核心观测</b>（`architecture-open-issues.md`）：
     * {@code max-incremental-scopes} 决定何时从增量退化为全量，而
     * {@code unknown-device-reconcile-timeout-ms} 是等待方的超时。
     * 二者之间缺少交叉约束 —— 必须先知道 <b>{@code mode=full} 的真实耗时是否显著小于该超时</b>，
     * 才能给这两个值定值。按 mode 分开记正是为了回答这个问题。
     *
     * @param mode   {@link #MODE_INCREMENTAL} 或 {@link #MODE_FULL}
     * @param millis 本轮耗时
     * @param scopes 本轮涉及的 scope 数；全量时为 0（全域重建不按 scope 计）
     */
    default void metadataConverge(String mode, long millis, int scopes) {
    }

    /**
     * 当前设备目录条目数 —— {@code device-catalog-max-entries} 的判据之一。
     */
    default void metadataCatalogEntries(long count) {
    }

    /**
     * 一次 MySQL 回源。
     *
     * <p>{@code device-db-fallback-batch-size} 与 {@code -max-concurrency} 的判据：
     * 回源批大小与耗时；稳态下回源应当极少（L1/L2 命中为主）。
     */
    default void metadataDbFallback(int batchSize, long millis) {
    }
}

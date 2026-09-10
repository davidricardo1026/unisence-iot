package com.unisence.iot.metadata;

/**
 * 收敛触发源（metadata-sync-bus.md §7.1）。
 *
 * <p>所有触发源最终都只调用 {@code observeDesiredHead(long, MetadataSyncTrigger)}，
 * 只做一件事：把目标水位抬高。<b>不存在 {@code refreshRules()} / {@code clearThingModel()}
 * 这类旁路入口</b> —— 有了旁路，「当前内存里是哪一代」就不再能从水位推断出来。
 *
 * <p>枚举值本身只用于日志与指标维度，不参与任何行为分支。
 */
public enum MetadataSyncTrigger {

    /**
     * 进程启动的全量一致性引导。
     */
    STARTUP,

    /**
     * Redis Pub/Sub 提示，正常链路的毫秒级发现路径。
     */
    REDIS_PUBSUB,

    /**
     * 订阅建立/重连后补读 Redis head，修复断线期间遗漏的提示。
     */
    REDIS_RECONNECTED,

    /**
     * 周期性 Redis head 探测，兜住订阅静默断开。
     */
    REDIS_HEAD_PROBE,

    /**
     * 周期性 MySQL 反熵，Redis 整体不可用时唯一仍然正确的发现路径，禁止关闭。
     */
    MYSQL_ANTI_ENTROPY,

    /**
     * engine 在同一 MySQL 事务内完成 device_create 后直接观察到的新水位。
     */
    DEVICE_CREATE,

    /**
     * 上一轮构建失败后的退避重试。
     */
    RETRY
}

package com.unisence.iot.redis;

/**
 * Redis 调用的观测回调（observability.md「engine 指标」）。
 *
 * <p><b>为什么是接口而不是直接注入指标类</b>：{@code RedisCommands} 属 {@code cn-metadata-sync}，
 * 而指标实现 {@code EngineMetrics} 属 {@code cn-service-engine}。engine 依赖 metadata-sync，
 * 反过来注入会形成反向依赖，违反模块边界（module-boundary.md）。因此这里只定义**回调契约**，
 * 由各宿主模块自行实现 —— rule-stream 将来若要观测同一批调用，实现自己的即可。
 *
 * <p>实现方必须<b>足够廉价且不抛异常</b>：它跑在 Redis 调用的热路径上，
 * 一次实现失败不得影响 Redis 调用本身的结果。
 */
public interface RedisObserver {

    /**
     * 什么都不做的实现；{@code RedisCommands} 未注入观察者时使用，避免热路径判空。
     */
    RedisObserver NOOP = new RedisObserver() {
    };

    /**
     * 一次 {@code send} 往返完成（无论成败）。
     *
     * @param command 命令名，取自 {@code Request} 的 command；值域有界，可安全作标签
     * @param millis  往返耗时
     */
    default void redisRoundtrip(String command, long millis) {
    }

    /**
     * 一次 {@code batch} 的分片命令数。
     */
    default void redisBatchSize(int size) {
    }

    /**
     * 一次调用失败。
     *
     * @param kind 失败归类，**禁止传异常 message** —— 那是无界字符串，会炸掉监控基数
     */
    default void redisError(String kind) {
    }
}

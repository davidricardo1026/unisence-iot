package com.unisence.iot.engine.config;

/**
 * {@code app.engine.redis.*}（engine-runtime-io.md §五）。
 *
 * <p>对应 {@code io.vertx.redis.client.RedisOptions} 上真实存在的参数。
 */
public record EngineRedisProperties(
    String connectionString,
    int poolMaxSize,
    int maxPoolWaiting,
    int maxWaitingHandlers,
    int pipelineBatchSize) {

    public EngineRedisProperties {
        requirePositive(poolMaxSize, "app.engine.redis.pool-max-size");
        requirePositive(maxPoolWaiting, "app.engine.redis.max-pool-waiting");
        requirePositive(maxWaitingHandlers, "app.engine.redis.max-waiting-handlers");
        requirePositive(pipelineBatchSize, "app.engine.redis.pipeline-batch-size");
        if (pipelineBatchSize > maxWaitingHandlers) {
            // maxWaitingHandlers 封顶了同时在途的响应处理器数；单次 pipeline 超过它会直接报错，
            // 且错误信息与「批太大」毫无字面关联 —— 必须在启动期堵死
            throw new IllegalArgumentException(
                "app.engine.redis.pipeline-batch-size(" + pipelineBatchSize
                    + ") 不得超过 max-waiting-handlers(" + maxWaitingHandlers + ")");
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正数，当前值: " + value);
        }
    }
}

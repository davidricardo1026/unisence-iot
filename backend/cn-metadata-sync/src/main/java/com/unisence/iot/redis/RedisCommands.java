package com.unisence.iot.redis;

import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * 全仓唯一的 Redis 访问入口（engine-runtime-io.md §5.1）。
 *
 * <p><b>存在的唯一理由是一条依赖事实</b>：
 * {@code io.vertx.redis.client.impl.types.ErrorType} <b>继承自 {@link Throwable} 而非
 * {@link Exception}</b>。因此业务代码里每一个 {@code catch (Exception)} 对 Redis 服务端错误
 * （{@code WRONGTYPE}、{@code OOM command not allowed}、{@code LOADING}、集群 {@code MOVED} 等）
 * 都是<b>无效防护</b> —— 异常会一路穿透直至线程终止。这不是假想：一条 Lua 报错曾就此
 * 终止 engine 的属性消费线程，链路永久停摆而进程仍然健康。
 *
 * <p>本类把「能包裹的」统一转成 {@link RedisCommandException}（一个 {@link RuntimeException}），
 * 使调用侧既有的 {@code catch (Exception)} 重新生效；<b>包裹不住的 JVM 级 {@link Error}</b>
 * （OOM、StackOverflow）原样放行 —— 那是进程级故障，应当让进程失败并由编排层重启，
 * 而不是被伪装成一次命令失败继续带病运行。
 *
 * <p><b>业务类禁止直接持有 {@link Redis}</b>。该约束的价值在于可验收：
 * 扫一遍「业务代码中不出现 {@code redis.send(} / {@code redis.batch(}」即可，
 * 而不必逐个类去审 catch 的类型写对没有。
 */
public final class RedisCommands {

    private final Redis redis;
    private final int pipelineBatchSize;
    /**
     * 恒非 null（缺省为 {@link RedisObserver#NOOP}），热路径上不必判空。
     */
    private final RedisObserver observer;

    public RedisCommands(Redis redis, int pipelineBatchSize) {
        this(redis, pipelineBatchSize, RedisObserver.NOOP);
    }

    public RedisCommands(Redis redis, int pipelineBatchSize, RedisObserver observer) {
        if (pipelineBatchSize <= 0) {
            throw new IllegalArgumentException("pipeline-batch-size 必须为正数: " + pipelineBatchSize);
        }
        this.redis = redis;
        this.pipelineBatchSize = pipelineBatchSize;
        this.observer = observer == null ? RedisObserver.NOOP : observer;
    }

    /**
     * 单条命令。
     *
     * @throws RedisCommandException 任何非 JVM 级失败
     */
    public Response send(Request request) {
        requireAvailable();
        long startedAt = System.nanoTime();
        try {
            return redis.send(request).await();
        } catch (Throwable t) {
            rethrowIfJvmError(t);
            // kind 取异常类名而非 message：后者是无界字符串，会把监控基数炸掉
            observer.redisError(t.getClass().getSimpleName());
            throw new RedisCommandException("Redis 命令执行失败", t);
        } finally {
            observer.redisRoundtrip(request.command().toString(), (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    /**
     * 分片 pipeline。
     *
     * <p><b>切片是强制的</b>：把一个 poll 批的全部命令塞进一次往返会击穿
     * {@code vertx-redis-client} 的 {@code maxWaitingHandlers}，而报错信息与「批太大」
     * 毫无字面关联 —— 又一条把配置错误伪装成运行时故障的路径。
     * 由本类统一执行，调用方不必各自记得切片。
     *
     * @return 与入参<b>等长且顺序一致</b>的响应，调用方可按下标回指原始请求
     * @throws RedisCommandException 任何非 JVM 级失败
     */
    public List<Response> batch(List<Request> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Response> all = new ArrayList<>(requests.size());
        for (int from = 0; from < requests.size(); from += pipelineBatchSize) {
            List<Request> slice = requests.subList(from, Math.min(from + pipelineBatchSize, requests.size()));
            observer.redisBatchSize(slice.size());
            all.addAll(sendSlice(slice));
        }
        return all;
    }

    /**
     * 发一片，且校验响应数量 —— 数量不符时后续按下标回指原始请求会静默错位。
     */
    private List<Response> sendSlice(List<Request> slice) {
        requireAvailable();
        List<Response> responses;
        try {
            responses = redis.batch(slice).await();
        } catch (Throwable t) {
            rethrowIfJvmError(t);
            throw new RedisCommandException("Redis pipeline 执行失败", t);
        }
        if (responses == null || responses.size() != slice.size()) {
            throw new RedisCommandException(
                "Redis pipeline 响应数量不一致: 期望=" + slice.size()
                    + " 实际=" + (responses == null ? "null" : responses.size()), null);
        }
        return responses;
    }

    /**
     * 取一条独占连接（订阅等需要脱离连接池的场景）。调用方负责关闭。
     */
    public RedisConnection connect() {
        requireAvailable();
        try {
            return redis.connect().await();
        } catch (Throwable t) {
            rethrowIfJvmError(t);
            throw new RedisCommandException("获取 Redis 连接失败", t);
        }
    }

    /**
     * 供 {@link RedisPipeline} 复用同一切片尺寸，避免两处各配一份而悄悄漂移。
     */
    public int pipelineBatchSize() {
        return pipelineBatchSize;
    }

    /**
     * 降级模式（{@code tryRedis} 返回 {@code null}）下给出明确失败，而不是在深处抛 NPE。
     *
     * <p>Redis 在元数据链路上只承担「发现延迟」不承担正确性，因此启动时它不可用并不阻止引导；
     * 但真正发命令时必须失败得可读 —— 一个 {@code NullPointerException} 只会让人去查空指针，
     * 而不是去查 Redis 连不上。
     */
    private void requireAvailable() {
        if (redis == null) {
            throw new RedisCommandException("Redis 客户端不可用（启动时连接失败，当前为降级模式）", null);
        }
    }

    /**
     * JVM 级错误原样放行：OOM、StackOverflow 不是「一次命令失败」，
     * 吞掉它们只会让进程在不可信状态下继续运行。
     */
    static void rethrowIfJvmError(Throwable t) {
        if (t instanceof Error error) {
            throw error;
        }
    }
}

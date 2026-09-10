package com.unisence.iot.redis;

import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.ResponseType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片 pipeline 执行器（engine-hotpath-optimization.md §三、engine-runtime-io.md §五）。
 *
 * <p><b>单次 {@code batch()} 必须有界</b>：把一个 poll 批的全部命令塞进一次往返，会击穿
 * {@code vertx-redis-client} 的 {@code maxWaitingHandlers}（在途响应处理器上限），
 * 而报错信息与「批太大」毫无字面关联 —— 又一条把配置错误伪装成运行时故障的路径。
 * 因此按 {@code pipeline-batch-size} 切片，逐片往返。
 *
 * <p>响应与入参**等长且顺序一致**，调用方可按下标回指原始请求。
 */
@Slf4j
public final class RedisPipeline {

    private final RedisCommands commands;
    private final int batchSize;

    public RedisPipeline(RedisCommands commands) {
        this.commands = commands;
        // 复用同一切片尺寸，避免两处各配一份而悄悄漂移。
        // 本类仍自行切片：NOSCRIPT 回退必须以「片」为单位重放，交给下层切片就拿不到片边界了
        this.batchSize = commands.pipelineBatchSize();
    }

    /**
     * 执行一组脚本调用。
     *
     * <p>任一分片遇 {@code NOSCRIPT} 时，对**该分片**重新加载脚本并以 {@code EVAL}
     * 整片重放一次。只回退一轮 —— 第二次 NOSCRIPT 意味着服务端在持续丢脚本
     * （如 SCRIPT FLUSH 循环），那是运维问题，重试只会掩盖它。
     */
    public List<Response> execute(List<ScriptCall> calls) {
        if (calls.isEmpty()) {
            return List.of();
        }
        List<Response> all = new ArrayList<>(calls.size());
        for (int from = 0; from < calls.size(); from += batchSize) {
            List<ScriptCall> slice = calls.subList(from, Math.min(from + batchSize, calls.size()));
            all.addAll(executeSlice(slice));
        }
        return all;
    }

    private List<Response> executeSlice(List<ScriptCall> slice) {
        List<Request> requests = new ArrayList<>(slice.size());
        for (ScriptCall call : slice) {
            requests.add(call.script().request(commands, call.keys(), call.args()));
        }
        List<Response> responses;
        try {
            responses = commands.batch(requests);
        } catch (RedisCommandException e) {
            // Throwable→RedisCommandException 的转换已由 RedisCommands 统一完成，
            // 这里只需分辨「是不是 NOSCRIPT」这一件事
            if (!RedisScript.isNoScript(e)) {
                throw e;
            }
            return replaySliceWithEval(slice);
        }
        if (containsNoScript(responses)) {
            return replaySliceWithEval(slice);
        }
        verifySize(responses, slice.size());
        return responses;
    }

    /**
     * 整片改用 {@code EVAL} 重放：脚本源码随命令下发，服务端顺带重新缓存。
     *
     * <p>重放后主动 {@code reload} 一次，让后续分片直接用上新 sha1，
     * 而不是每片都先撞一次 NOSCRIPT 再回退。
     */
    private List<Response> replaySliceWithEval(List<ScriptCall> slice) {
        log.warn("pipeline 遇 NOSCRIPT，本片改用 EVAL 重放并重新加载脚本: size={}", slice.size());
        List<Request> fallback = new ArrayList<>(slice.size());
        for (ScriptCall call : slice) {
            fallback.add(call.script().fallbackRequest(call.keys(), call.args()));
        }
        List<Response> responses = commands.batch(fallback);
        verifySize(responses, slice.size());
        if (containsNoScript(responses)) {
            throw new IllegalStateException("EVAL 重放后仍返回 NOSCRIPT，服务端可能在持续丢弃脚本");
        }
        slice.stream().map(ScriptCall::script).distinct().forEach(script -> script.reload(commands));
        return responses;
    }

    private static boolean containsNoScript(List<Response> responses) {
        if (responses == null) {
            return false;
        }
        for (Response response : responses) {
            if (response != null && response.type() == ResponseType.ERROR
                && response.toString().contains("NOSCRIPT")) {
                return true;
            }
        }
        return false;
    }

    private static void verifySize(List<Response> responses, int expected) {
        if (responses == null || responses.size() != expected) {
            throw new IllegalStateException(
                "Redis pipeline 响应数量不一致: 期望=" + expected
                    + " 实际=" + (responses == null ? "null" : responses.size()));
        }
    }

    /**
     * 一次脚本调用。KEYS 与 ARGV 分开传，调用方不必自己数 numkeys。
     */
    public record ScriptCall(RedisScript script, List<String> keys, List<?> args) {

        public ScriptCall {
            if (script == null) {
                throw new IllegalArgumentException("script 不能为空");
            }
            keys = List.copyOf(keys);
            args = List.copyOf(args);
        }
    }
}

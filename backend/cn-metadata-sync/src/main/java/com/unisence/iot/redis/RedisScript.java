package com.unisence.iot.redis;

import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一段 Lua 脚本及其服务端 sha1 缓存（engine-hotpath-optimization.md §三）。
 *
 * <p><b>禁止裸 {@code EVAL}</b>：把脚本源码当参数逐条重传，在热路径上会让「指令描述」
 * 占掉大部分网络字节 —— 最新属性 CAS 脚本约 330B，而 sha1 只要 40B。
 * 一个 poll 批展开成数千条命令时，这个差额就是数量级的。
 *
 * <p>线程安全：sha1 用 {@link AtomicReference} 承载，重复加载是幂等的
 * （{@code SCRIPT LOAD} 对同一脚本恒返回同一 sha1），因此并发首次加载**不需要加锁** ——
 * 多加载一次的代价远小于在热路径上放一把锁。
 *
 * <p>按脚本各持一个实例，建议声明为 {@code static final}。
 */
@Slf4j
public final class RedisScript {

    private final String source;
    private final AtomicReference<String> sha1 = new AtomicReference<>();

    private RedisScript(String source) {
        this.source = source;
    }

    public static RedisScript of(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Lua 脚本源码不能为空");
        }
        return new RedisScript(source);
    }

    /**
     * 单次执行；本进程尚未加载过该脚本时先同步 {@code SCRIPT LOAD}。
     *
     * <p>遇 {@code NOSCRIPT}（Redis 重启、{@code SCRIPT FLUSH}、故障转移到未加载的副本）
     * 重新加载后重放一次。只回退一轮：第二次 NOSCRIPT 意味着服务端在持续丢脚本，
     * 那是运维问题，继续重试只会掩盖它。
     */
    public Response execute(RedisCommands commands, List<String> keys, List<?> args) {
        try {
            return commands.send(evalShaRequest(load(commands), keys, args));
        } catch (RedisCommandException e) {
            if (!isNoScript(e)) {
                throw e;
            }
            log.warn("EVALSHA 遇 NOSCRIPT，重新加载脚本后重放一次");
            sha1.set(null);
            return commands.send(evalShaRequest(load(commands), keys, args));
        }
    }

    /**
     * JVM 级错误（OOM、StackOverflow 等）原样放行，不得被当作命令失败吞掉。
     *
     * <p>其余一律转成 {@link RedisCommandException} —— 关键是 {@code ErrorType}
     * 继承自 {@code Throwable} 而非 {@code Exception}，不转换就会穿透所有
     * {@code catch (Exception)} 直至线程终止。
     */
    /**
     * 构造一条可放进 pipeline 的 {@code EVALSHA} 请求。
     *
     * <p>批量场景的 NOSCRIPT 回退由 {@link RedisPipeline} 统一处理 ——
     * 单条请求自己无法回退，它不知道自己在哪个批里、也拿不到同批其它响应。
     */
    public Request request(RedisCommands commands, List<String> keys, List<?> args) {
        return evalShaRequest(load(commands), keys, args);
    }

    /**
     * 等价的 {@code EVAL} 请求（携带脚本源码），供 pipeline 回退整片重放时使用。
     */
    public Request fallbackRequest(List<String> keys, List<?> args) {
        return fill(Request.cmd(Command.EVAL).arg(source), keys, args);
    }

    /**
     * 强制重新加载，返回新的 sha1。{@link RedisPipeline} 在整片回退后调用，
     * 使后续批次直接用上新 sha1，而不是每批都撞一次 NOSCRIPT。
     */
    String reload(RedisCommands commands) {
        sha1.set(null);
        return load(commands);
    }

    private String load(RedisCommands commands) {
        String cached = sha1.get();
        if (cached != null) {
            return cached;
        }
        Response response = commands.send(Request.cmd(Command.SCRIPT).arg("LOAD").arg(source));
        if (response == null) {
            throw new IllegalStateException("SCRIPT LOAD 未返回 sha1");
        }
        String loaded = response.toString();
        sha1.set(loaded);
        log.info("Lua 脚本已加载: sha1={} 源码字节={}", loaded, source.length());
        return loaded;
    }

    private Request evalShaRequest(String sha, List<String> keys, List<?> args) {
        return fill(Request.cmd(Command.EVALSHA).arg(sha), keys, args);
    }

    /**
     * ARGV 允许 {@code String} 与 {@code byte[]} 两种形态。
     *
     * <p>二进制是必需的：设备 L2 投影是 MessagePack 字节，转成 String 会按字符集重新编码，
     * 静默破坏数据。其余类型一律拒绝 —— 让隐式 {@code toString()} 参与线上协议，
     * 是一条只会在特定取值下暴露的错误路径。
     */
    private static Request fill(Request request, List<String> keys, List<?> args) {
        request.arg(Integer.toString(keys.size()));
        keys.forEach(request::arg);
        for (Object arg : args) {
            switch (arg) {
                case String text -> request.arg(text);
                case byte[] binary -> request.arg(binary);
                case null -> throw new IllegalArgumentException("Lua 参数不能为 null");
                default -> throw new IllegalArgumentException(
                    "Lua 参数只支持 String 与 byte[]，实际: " + arg.getClass().getName());
            }
        }
        return request;
    }

    /**
     * Redis 对未知 sha1 返回的错误以 {@code NOSCRIPT} 开头。逐层查因，
     * 因为 vertx-redis-client 会把服务端错误包在 ErrorType 里再向上抛。
     */
    static boolean isNoScript(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("NOSCRIPT")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}

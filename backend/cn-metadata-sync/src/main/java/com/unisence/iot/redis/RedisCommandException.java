package com.unisence.iot.redis;

/**
 * Redis 命令失败。
 *
 * <p><b>存在理由是一个真实的陷阱</b>：{@code io.vertx.redis.client.impl.types.ErrorType}
 * （服务端返回的 {@code -ERR ...} 响应）继承自 <b>{@link Throwable} 而非 {@link Exception}</b>。
 * 因此 {@code Future.await()} 把它抛出来时，调用侧任何 {@code catch (Exception)} 都<b>接不住</b> ——
 * 异常直接逃逸到线程顶层，消费循环静默死亡，且日志里只有一行 {@code Exception in thread ...}，
 * 没有任何业务上下文。
 *
 * <p>实测复现：一条格式不符的历史值让 Lua 脚本报错，`engine-poll-iot.raw.data` 线程当场终止，
 * 整条属性消费链路停摆。
 *
 * <p>因此 {@link RedisCommands}（以及构建其上的 {@link RedisScript} / {@link RedisPipeline}）
 * 统一把它转成本异常（{@code RuntimeException}），使既有的失败分类与重放逻辑重新生效。
 *
 * <p><b>JVM 级 {@link Error} 不在此列</b>：OOM、StackOverflow 会被原样放行，
 * 由进程失败 + 编排层重启处置 —— 包裹它们等于让进程在不可信状态下继续运行。
 */
public final class RedisCommandException extends RuntimeException {

    public RedisCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}

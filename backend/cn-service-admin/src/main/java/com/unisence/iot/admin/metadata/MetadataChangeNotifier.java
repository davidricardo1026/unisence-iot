package com.unisence.iot.admin.metadata;

import com.unisence.iot.common.metadata.MetadataRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 提交后向 Redis 发布变更提示（metadata-sync-bus.md §5.1、§13.2）。
 *
 * <p><b>发布必须发生在 MySQL 提交之后</b>，因此发布失败绝不能回滚或伪装成业务保存失败 ——
 * 数据已经落库，业务上是成功的。丢失的只是「毫秒级发现」这一层优化，
 * engine 的 5s Redis head 探测与 30s MySQL 反熵会把它补回来。
 *
 * <p>这也是为什么本类不需要重试、不需要本地队列、不需要死信：正确性完全由 MySQL 侧保证，
 * Redis 在这条链路上是纯性能层。
 */
@Slf4j
@Component
public class MetadataChangeNotifier {

    /**
     * 原子执行「取 max 后写回 + 发布」。
     *
     * <p><b>为什么必须 max 而不是直接 SET</b>：两个写事务可能按 44、43 的顺序调用本脚本
     * （先提交的事务未必先执行 afterCommit 回调）。直接 {@code SET 43} 会让镜像倒退，
     * 落后的 engine 探测到更小的 head 后就不会再收敛。
     *
     * <p><b>为什么用字符串比大小</b>：水位是 {@code BIGINT}，而 Lua 的 number 是双精度，
     * 超过 {@code 2^53} 后 {@code tonumber} 会丢精度。无前导零的十进制字符串
     * 「先比长度、等长再比字典序」与数值序完全一致，且无精度上限。
     *
     * <p>脚本只比较一个整数、写一个 key、发布一条消息 —— 禁止在此放业务查询或循环。
     */
    private static final String LUA_MAX_AND_PUBLISH = """
        local current = redis.call('GET', KEYS[1]) or '0'
        local incoming = ARGV[1]
        local incomingIsGreater =
            (#incoming > #current) or (#incoming == #current and incoming > current)
        if incomingIsGreater then
            current = incoming
            redis.call('SET', KEYS[1], current)
        end
        redis.call('PUBLISH', ARGV[2],
            cjson.encode({schemaVersion = 1, desiredHead = current}))
        return current
        """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> script;

    public MetadataChangeNotifier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        // DefaultRedisScript 内部走 EVALSHA，遇 NOSCRIPT 自动回退 EVAL 并重新缓存，
        // 正是契约要求的「SCRIPT LOAD + EVALSHA，NOSCRIPT 时重新加载」
        this.script = new DefaultRedisScript<>(LUA_MAX_AND_PUBLISH, String.class);
    }

    /**
     * 注册事务提交后的提示发布。
     *
     * <p>用 {@code afterCommit} 而不是在事务内直接发：事务内发布会在业务随后回滚时
     * 把不存在的水位提示出去，engine 会去追一个永远到不了的 head。
     *
     * <p>无事务上下文时立即发布 —— 这条路径只用于运维工具类调用，业务链路一律走事务。
     */
    public void publishAfterCommit(long committedSeq) {
        if (committedSeq < 0) {
            throw new IllegalArgumentException("committedSeq 不能为负: " + committedSeq);
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(committedSeq);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(committedSeq);
            }
        });
    }

    /**
     * 供 admin「重新提醒」运维入口直接调用：只唤醒落后实例，不产生新水位。
     */
    public void publish(long committedSeq) {
        try {
            String head = redisTemplate.execute(
                script,
                List.of(MetadataRedisKeys.HEAD),
                Long.toString(committedSeq),
                MetadataRedisKeys.CHANGED_CHANNEL);
            log.debug("元数据变更提示已发布: committedSeq={} redisHead={}", committedSeq, head);
        } catch (Exception e) {
            // 业务事务已提交，此处绝不能向上抛：engine 的 Redis head 探测与 MySQL 反熵会补发现
            log.error("发布元数据变更提示失败，将由 engine 反熵兜底: committedSeq={}", committedSeq, e);
        }
    }
}

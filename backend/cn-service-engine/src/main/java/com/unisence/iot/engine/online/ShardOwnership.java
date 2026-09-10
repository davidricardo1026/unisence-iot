package com.unisence.iot.engine.online;

import com.unisence.iot.common.metadata.MetadataRedisKeys;
import com.unisence.iot.redis.RedisCommands;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * 用 rendezvous hash 决定「哪些 shard 归本实例」（device-online-state-design.md §6.1）。
 *
 * <p><b>为什么是 rendezvous 而不是持久分配表</b>：分配表需要有人维护、有人在实例退出时回收，
 * 于是又要一套选主与租约。rendezvous 是纯函数 —— 每个实例用同一组存活实例集合独立算，
 * 结果自然一致；实例退出后它的 TTL 到期，下一轮所有实例算出的 owner 自动变更，无需任何协调。
 *
 * <p>实例集合直接复用元数据总线的 TTL 索引（{@code unisence:{metadata}:instances}），
 * 不再单独维护一份成员列表 —— 两份成员视图迟早会不一致。
 *
 * <p>集合短暂不一致最多导致重复抢锁或重复消费：扫描侧有 per-shard 锁 + Lua 跳变守卫，
 * drainer 侧有 consumer group（同一条新项只会交给组内一个 consumer），正确性不依赖归属的准确性。
 */
@Slf4j
public class ShardOwnership {

    /**
     * 降级告警的静默窗口：Redis 持续不可用时本告警每轮都会触发（H16）。
     */
    private static final long DEGRADED_LOG_WINDOW_MS = 5000;
    private final java.util.concurrent.atomic.AtomicLong degradedLogAt =
        new java.util.concurrent.atomic.AtomicLong();

    private final RedisCommands commands;
    private final String instanceId;

    public ShardOwnership(RedisCommands commands, String instanceId) {
        this.commands = commands;
        this.instanceId = instanceId;
    }

    /**
     * 计算本实例应负责的 shard 集合。
     *
     * <p>取不到存活实例列表时<b>退化为「全部 shard 归自己」</b>而不是「一个都不做」：
     * 前者最坏是多实例重复劳动（有锁与消费组兜底），后者会让所有跳变彻底没人落库。
     */
    public BitSet ownedShards() {
        List<String> instances = aliveInstances();
        BitSet owned = new BitSet(OnlineShards.SHARD_COUNT);
        if (instances.isEmpty()) {
            // 抑制：Redis 不可用时本方法每轮都进这一支，实测 45s 停机刷出 60 万行（H16）。
            // 首次仍立即落盘 —— 这条是「实例发现降级」的重要信号，不能完全静默
            if (degradedLogAt.get() < System.currentTimeMillis()) {
                degradedLogAt.set(System.currentTimeMillis() + DEGRADED_LOG_WINDOW_MS);
                log.warn("未取得存活实例列表，本实例暂时接管全部 shard（该告警 {}ms 内不重复）",
                         DEGRADED_LOG_WINDOW_MS);
            }
            owned.set(0, OnlineShards.SHARD_COUNT);
            return owned;
        }
        for (int shard = 0; shard < OnlineShards.SHARD_COUNT; shard++) {
            if (instanceId.equals(preferredOwner(shard, instances))) {
                owned.set(shard);
            }
        }
        return owned;
    }

    /**
     * Highest Random Weight：对每个候选算 {@code hash(shard, instanceId)}，取最大者。
     *
     * <p>它的关键性质是<b>最小扰动</b>：增删一个实例时，只有原本归属它的那部分 shard 会重新分配，
     * 其余 shard 的归属完全不变。普通的 {@code hash % n} 在实例数变化时会让几乎所有 shard 重排。
     *
     * <p>并列时按 instanceId 字典序取大，保证所有实例算出同一个结果。
     */
    private static String preferredOwner(int shard, List<String> instances) {
        String best = null;
        long bestWeight = Long.MIN_VALUE;
        for (String instance : instances) {
            long weight = weight(shard, instance);
            if (weight > bestWeight || (weight == bestWeight && instance.compareTo(best) > 0)) {
                bestWeight = weight;
                best = instance;
            }
        }
        return best;
    }

    /**
     * CRC32C 与设备路由共用，保证跨进程、跨版本稳定 —— 禁止用 {@code String.hashCode()}。
     */
    private static long weight(int shard, String instanceId) {
        CRC32C crc = new CRC32C();
        crc.update((shard + ":" + instanceId).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /**
     * 读元数据总线的实例 TTL 索引；过期成员先惰性清理再取。
     */
    private List<String> aliveInstances() {
        try {
            long now = System.currentTimeMillis();
            Response response = commands.send(Request.cmd(Command.ZRANGEBYSCORE)
                                               .arg(MetadataRedisKeys.INSTANCE_INDEX)
                                               .arg(Long.toString(now))
                                                  .arg("+inf"));
            if (response == null || response.size() == 0) {
                return List.of();
            }
            List<String> instances = new ArrayList<>(response.size());
            for (Response item : response) {
                if (item != null) {
                    instances.add(item.toString());
                }
            }
            // 排序让并列比较在所有实例上得到同一结果
            Collections.sort(instances);
            return instances;
        } catch (Exception e) {
            log.error("读取存活实例列表失败，本轮退化为接管全部 shard", e);
            return List.of();
        }
    }
}

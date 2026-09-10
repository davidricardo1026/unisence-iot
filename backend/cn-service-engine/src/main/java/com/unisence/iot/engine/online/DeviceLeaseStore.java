package com.unisence.iot.engine.online;

import com.unisence.iot.redis.RedisCommands;
import com.unisence.iot.redis.RedisPipeline;
import com.unisence.iot.redis.RedisScript;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 设备与驱动租约的 Redis 访问（device-online-state-design.md §四、§五、§六）。
 *
 * <p>用 {@code vertx-redis-client}（Netty 原生 RESP 实现）配 {@code Future.await()} 写成顺序代码：
 * 非阻塞 I/O + 无回调链，且天然免疫虚拟线程 pinning。
 *
 * <p><b>全部键按 256 shard 分片</b>（{@link OnlineShards}）。百万设备压在单 ZSET 上，
 * 一次范围查询要在百万成员里定位，且所有写入集中到一个 Cluster slot。
 *
 * <p><b>租约 ZSET 的分数存「过期时刻」而非「最后可见时刻」</b>：不同产品 TTL 不同，
 * 若存 lastSeen 则「谁过期了」无法用一次范围查询得出。存 {@code lastSeen + ttl} 后，
 * 判活退化为每 shard 一次 {@code ZRANGEBYSCORE -inf now}。
 */
@Slf4j
public class DeviceLeaseStore {

    /**
     * 续租并原子判定上线跳变。
     *
     * <p>必须原子：多实例并发消费同一设备的消息时，「读旧状态 → 判断 → 写新状态」若非原子，
     * 会产生重复上线跳变、重复日志与重复告警。
     *
     * <p><b>跳变与入队在同一个脚本内完成</b>：Lua 一旦返回，跳变就已经落进 transition stream，
     * 因此 engine 随后宕机也不会丢掉这次跳变 —— 这正是「禁止先放进仅进程内队列」的原因。
     * 返回值是 stream id 而不是布尔值，调用方无需、也不应该自己去落库。
     *
     * <p>四个 key 同属一个 {@code {oNNN}} shard，因此 Cluster 下同 slot，脚本可原子执行。
     */
    private static final RedisScript LUA_RENEW = RedisScript.of("""
        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
        redis.call('HSET', KEYS[3], ARGV[1], ARGV[3])
        local prev = redis.call('HGET', KEYS[2], ARGV[1])
        if prev == ARGV[4] then
            return false
        end
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
        return redis.call('XADD', KEYS[4], '*',
            'deviceKey', ARGV[1], 'target', ARGV[4],
            'reason', 'connect', 'changedAt', ARGV[5])
                                                                    """);

    /**
     * 过期判定并原子跳变。重新校验分数以处理「扫描取出候选后、判定前恰好续租」的竞态。
     *
     * <p><b>过期条目必须从 lease ZSET 移除</b>：否则 {@code LIMIT} 会永远返回最早的那批已过期成员，
     * 后面的设备永远轮不到，形成饥饿。状态与 owner Hash 保留供判定与排查；设备恢复时
     * {@code LUA_RENEW} 会重新 {@code ZADD} 回来。
     *
     * <p>移出 ZSET 不会因进程崩溃而丢动作 —— 跳变已由本脚本可靠写进 stream。
     */
    private static final RedisScript LUA_EXPIRE = RedisScript.of("""
        local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
        if (not score) or tonumber(score) > tonumber(ARGV[2]) then
            return false
        end
        local prev = redis.call('HGET', KEYS[2], ARGV[1])
        redis.call('ZREM', KEYS[1], ARGV[1])
        if prev == ARGV[3] then
            return false
        end
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
        local reason = ARGV[3] == '2' and 'heartbeat_timeout' or 'driver_unreachable'
        return redis.call('XADD', KEYS[3], '*',
            'deviceKey', ARGV[1], 'target', ARGV[3],
            'reason', reason, 'changedAt', ARGV[2])
                                                                     """);

    /**
     * 仅当锁仍由 token 持有时续期。比较与 PEXPIRE 必须在同一个 Lua 内原子执行。
     */
    private static final RedisScript LUA_RENEW_SCAN_LOCK = RedisScript.of("""
                                                                              if redis.call('GET', KEYS[1]) == ARGV[1] then
                                                                                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
                                                                              end
                                                                              return 0
                                                                              """);

    /**
     * compare-and-delete：裸 {@code DEL} 会删掉别人刚抢到的锁。
     */
    private static final RedisScript LUA_RELEASE_SCAN_LOCK = RedisScript.of("""
                                                                                if redis.call('GET', KEYS[1]) == ARGV[1] then
                                                                                  return redis.call('DEL', KEYS[1])
                                                                                end
                                                                                return 0
                                                                                """);

    private final RedisCommands commands;
    private final RedisPipeline pipeline;

    public DeviceLeaseStore(RedisCommands commands, RedisPipeline pipeline) {
        this.commands = commands;
        this.pipeline = pipeline;
    }

    /**
     * 批量续租，按 shard 分组后用 pipeline 一次往返。
     *
     * <p>调用方已完成 §5.3 的进程内节流，此处不再去重。
     *
     * <p>返回值只用于日志与观测：跳变已由 Lua 写入 stream，由 {@code OnlineTransitionVerticle}
     * 可靠落库，<b>调用方不得据此自行落库</b>。
     *
     * @return 本次真正完成 → ONLINE 跳变的 deviceKey
     */
    public Set<String> renewDevices(List<LeaseRenewal> renewals) {
        if (renewals.isEmpty()) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        List<RedisPipeline.ScriptCall> batch = new ArrayList<>(renewals.size());
        for (LeaseRenewal renewal : renewals) {
            int shard = OnlineShards.of(renewal.deviceKey());
            batch.add(new RedisPipeline.ScriptCall(
                LUA_RENEW,
                List.of(OnlineShards.lease(shard),
                        OnlineShards.state(shard),
                        OnlineShards.owner(shard),
                        OnlineShards.transition(shard)),
                List.of(renewal.deviceKey(),
                        Long.toString(renewal.expireAtMs()),
                        renewal.ownerId(),
                        DeviceOnlineState.ONLINE.codeText(),
                        Long.toString(now))));
        }
        List<Response> responses = pipeline.execute(batch);

        Set<String> transitioned = new HashSet<>();
        for (int i = 0; i < responses.size(); i++) {
            Response response = responses.get(i);
            // 返回 stream id（非空）即表示本次真正发生了跳变；false 时 Redis 回 nil
            if (response != null) {
                transitioned.add(renewals.get(i).deviceKey());
            }
        }
        return transitioned;
    }

    /**
     * 续驱动实例租约。驱动实例数量小，不分片。
     */
    public void renewService(String ownerId, long expireAtMs) {
        commands.send(Request.cmd(Command.ZADD).arg(OnlineShards.KEY_SVC_LEASE)
                          .arg(Long.toString(expireAtMs)).arg(ownerId));
    }

    /**
     * 已失联的驱动实例集合。
     */
    public Set<String> expiredServices(long nowMs) {
        Response response = commands.send(Request.cmd(Command.ZRANGEBYSCORE)
                                              .arg(OnlineShards.KEY_SVC_LEASE).arg("-inf").arg(Long.toString(nowMs)));
        return toStringSet(response);
    }

    /**
     * 指定 shard 中租约已过期的设备键，最多 limit 个。
     */
    public List<String> expiredDevices(int shard, long nowMs, int limit) {
        Response response = commands.send(Request.cmd(Command.ZRANGEBYSCORE)
                                           .arg(OnlineShards.lease(shard)).arg("-inf").arg(Long.toString(nowMs))
                                              .arg("LIMIT").arg(0).arg(limit));
        return new ArrayList<>(toStringSet(response));
    }

    /**
     * 批量取归属，避免逐台 HGET 往返。
     *
     * <p>入参必须同属一个 shard —— 跨 shard 的 key 在 Cluster 下不同 slot，无法合成一次 HMGET。
     */
    public Map<String, String> owners(int shard, List<String> deviceKeys) {
        if (deviceKeys.isEmpty()) {
            return Map.of();
        }
        Request request = Request.cmd(Command.HMGET).arg(OnlineShards.owner(shard));
        deviceKeys.forEach(request::arg);
        Response response = commands.send(request);
        Map<String, String> result = new HashMap<>(deviceKeys.size());
        for (int i = 0; i < deviceKeys.size(); i++) {
            Response item = response == null ? null : response.get(i);
            result.put(deviceKeys.get(i), item == null ? null : item.toString());
        }
        return result;
    }

    /**
     * 原子地把设备置为目标态；内部重新校验租约分数，期间若已续租则放弃。
     *
     * <p>跳变同样由 Lua 直接写入 transition stream。
     *
     * @return {@code true} 表示本次调用完成了跳变
     */
    public boolean expireDevice(String deviceKey, long nowMs, DeviceOnlineState target) {
        int shard = OnlineShards.of(deviceKey);
        Response response = LUA_EXPIRE.execute(
            commands,
            List.of(OnlineShards.lease(shard),
                    OnlineShards.state(shard),
                    OnlineShards.transition(shard)),
            List.of(deviceKey, Long.toString(nowMs), target.codeText()));
        return response != null;
    }

    /**
     * 抢占某 shard 的扫描 token。
     *
     * <p>token 是 {@code {instanceId}:{scanUlid}}，释放时必须 compare-and-delete ——
     * 裸 {@code DEL} 会删掉别人刚抢到的锁。
     */
    public boolean tryAcquireScanLock(int shard, String token, long lockTtlMs) {
        Response response = commands.send(Request.cmd(Command.SET)
                                           .arg(OnlineShards.scanLock(shard)).arg(token)
                                              .arg("NX").arg("PX").arg(Long.toString(lockTtlMs)));
        return response != null;
    }

    /**
     * 仅当锁仍由 token 持有时续期。
     *
     * @return {@code false} 表示锁已过期或已由其他实例持有，调用方必须立即停止扫描该 shard
     */
    public boolean renewScanLock(int shard, String token, long lockTtlMs) {
        Response response = LUA_RENEW_SCAN_LOCK.execute(
            commands,
            List.of(OnlineShards.scanLock(shard)),
            List.of(token, Long.toString(lockTtlMs)));
        return response != null && response.toLong() == 1L;
    }

    /**
     * compare-and-delete 释放自己的 token；不是自己的就不动。
     */
    public void releaseScanLock(int shard, String token) {
        try {
            LUA_RELEASE_SCAN_LOCK.execute(
                commands,
                List.of(OnlineShards.scanLock(shard)),
                List.of(token));
        } catch (Exception e) {
            // 释放失败无伤：TTL 会兜底，最多让该 shard 空转一个周期
            log.warn("释放扫描锁失败，等待 TTL 过期: shard={}", shard, e);
        }
    }

    /**
     * 设备删除时清理该 shard 三个结构中的残留。
     */
    public void evict(String deviceKey) {
        int shard = OnlineShards.of(deviceKey);
        commands.batch(List.of(
            Request.cmd(Command.ZREM).arg(OnlineShards.lease(shard)).arg(deviceKey),
            Request.cmd(Command.HDEL).arg(OnlineShards.state(shard)).arg(deviceKey),
            Request.cmd(Command.HDEL).arg(OnlineShards.owner(shard)).arg(deviceKey)));
    }

    private static Set<String> toStringSet(Response response) {
        if (response == null || response.size() == 0) {
            return Set.of();
        }
        Set<String> values = new HashSet<>(response.size());
        for (Response item : response) {
            if (item != null) {
                values.add(item.toString());
            }
        }
        return values;
    }
}

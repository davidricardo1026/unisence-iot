package com.unisence.iot.metadata;

import com.unisence.iot.common.metadata.MetadataRedisKeys;
import com.unisence.iot.redis.RedisCommands;
import com.unisence.iot.redis.RedisPipeline;
import com.unisence.iot.redis.RedisScript;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 设备投影的 Redis L2（metadata-sync-bus.md §6.6）。
 *
 * <p><b>分桶而不是百万顶级 key</b>：Redis 每个顶级 key 都要带 robj、dictEntry、SDS 等结构，
 * 百万个小 key 的元数据开销远超数据本身。4096 个 Hash 把这部分摊薄，同时
 * {@code {dNNNN}} 的 hash tag 让同桶操作落同一 Cluster slot，可以 pipeline / Lua。
 * 反过来若沿用 {@code {metadata}} 单一 tag，百万设备会全部挤进一个 slot，Cluster 就白建了。
 *
 * <p><b>L2 的 absence 从不代表设备不存在</b>：条目可能被淘汰、Redis 可能被清空。
 * 「不存在」只能由本地 Bloom 明确否定或 MySQL 查询确认。
 */
@Slf4j
public final class DeviceL2Store {

    /**
     * 版本守卫写入。
     *
     * <p>并发填充与乱序写是常态：两个消息线程可能同时 miss 同一台设备，各自回源后写回，
     * 而先发出的请求未必先到达 Redis。没有守卫，后到的旧值会覆盖新值，
     * 且因为 Hash field 没有 TTL，这个旧值会一直留着。
     *
     * <p>脚本只做<b>定长切片 + 等长字符串比较</b>，不解析 MessagePack 载荷、不用 cjson：
     * Lua number 是双精度，比较 BIGINT 级 deviceId 会丢精度。
     *
     * <p>接受条件：新 deviceId 更大（设备被删后重建），或同一 deviceId 且两个版本都不小于旧值。
     */
    private static final RedisScript LUA_GUARDED_HSET = RedisScript.of("""
        local existing = redis.call('HGET', KEYS[1], ARGV[1])
        local incoming = ARGV[2]
        if string.sub(incoming, 1, 2) ~= '01' then
            return 0
        end
        if not existing then
            redis.call('HSET', KEYS[1], ARGV[1], incoming)
            return 1
        end
        if string.sub(existing, 1, 2) ~= '01' then
            redis.call('HSET', KEYS[1], ARGV[1], incoming)
            return 1
        end
        local oldId  = string.sub(existing, 4, 22)
        local newId  = string.sub(incoming, 4, 22)
        local oldRow = string.sub(existing, 24, 33)
        local newRow = string.sub(incoming, 24, 33)
        local oldForm = string.sub(existing, 35, 44)
        local newForm = string.sub(incoming, 35, 44)
        if newId > oldId then
            redis.call('HSET', KEYS[1], ARGV[1], incoming)
            return 1
        end
        if newId == oldId and newRow >= oldRow and newForm >= oldForm then
            redis.call('HSET', KEYS[1], ARGV[1], incoming)
            return 1
        end
        return 0
                                                                           """);

    private final RedisCommands commands;
    private final RedisPipeline pipeline;
    private final boolean available;

    public DeviceL2Store(RedisCommands commands, RedisPipeline pipeline, boolean available) {
        this.commands = commands;
        this.pipeline = pipeline;
        this.available = available;
    }

    /**
     * 按桶分组的批量读取。
     *
     * <p>先按桶聚合再 pipeline：同桶的多个 field 合成一次 {@code HMGET}，
     * 逐个 {@code HGET} 会把一批 500 台设备变成 500 次往返。
     *
     * @return 命中的原始字节；<b>调用方仍须逐条做双版本校验</b>
     */
    public Map<DeviceRef, DeviceCacheEnvelope> getAll(Set<DeviceRef> refs) {
        if (!available || refs.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<DeviceRef>> byBucket = new HashMap<>();
        for (DeviceRef ref : refs) {
            byBucket.computeIfAbsent(ref.bucket(MetadataRedisKeys.DEVICE_META_BUCKETS), k -> new ArrayList<>())
                .add(ref);
        }
        List<Request> requests = new ArrayList<>(byBucket.size());
        List<List<DeviceRef>> order = new ArrayList<>(byBucket.size());
        for (var entry : byBucket.entrySet()) {
            Request request = Request.cmd(Command.HMGET)
                .arg(MetadataRedisKeys.deviceMetaBucket(entry.getKey()));
            for (DeviceRef ref : entry.getValue()) {
                request.arg(ref.hashField());
            }
            requests.add(request);
            order.add(entry.getValue());
        }
        try {
            List<Response> responses = commands.batch(requests);
            Map<DeviceRef, DeviceCacheEnvelope> result = new LinkedHashMap<>();
            for (int i = 0; i < responses.size(); i++) {
                Response response = responses.get(i);
                List<DeviceRef> bucketRefs = order.get(i);
                if (response == null) {
                    continue;
                }
                for (int j = 0; j < bucketRefs.size() && j < response.size(); j++) {
                    Response item = response.get(j);
                    if (item == null) {
                        continue;
                    }
                    DeviceCacheEnvelope envelope = DeviceCacheCodec.decode(item.toBytes());
                    if (envelope != null) {
                        result.put(bucketRefs.get(j), envelope);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            // L2 故障不阻断消费：退化成 MySQL 批量回源，Redis 是性能层而非生存依赖
            log.error("读取设备 L2 缓存失败，本批退化为回源: refs={}", refs.size(), e);
            return Map.of();
        }
    }

    /**
     * 版本守卫写入一批条目。写失败只降低命中率，不影响正确性。
     */
    public void putAll(Map<DeviceRef, DeviceCacheEnvelope> entries) {
        if (!available || entries.isEmpty()) {
            return;
        }
        List<RedisPipeline.ScriptCall> calls = new ArrayList<>(entries.size());
        for (var entry : entries.entrySet()) {
            DeviceRef ref = entry.getKey();
            byte[] encoded;
            try {
                encoded = DeviceCacheCodec.encode(entry.getValue());
            } catch (RuntimeException e) {
                log.error("设备投影编码失败，跳过写 L2: ref={}", ref.deviceKey(), e);
                continue;
            }
            // encoded 是 MessagePack 二进制，必须以 byte[] 入参 —— 转 String 会按字符集重编码而静默破坏数据
            calls.add(new RedisPipeline.ScriptCall(
                LUA_GUARDED_HSET,
                List.of(MetadataRedisKeys.deviceMetaBucket(
                    ref.bucket(MetadataRedisKeys.DEVICE_META_BUCKETS))),
                List.of(ref.hashField(), encoded)));
        }
        if (calls.isEmpty()) {
            return;
        }
        try {
            pipeline.execute(calls);
        } catch (Exception e) {
            log.error("写入设备 L2 缓存失败: entries={}", calls.size(), e);
        }
    }

    /**
     * 幂等删除。
     *
     * <p>Hash field 没有独立 TTL，因此淘汰只能靠这里的显式删除 + 低峰 janitor。
     * 好在删漏了也不会读到旧值 —— 双版本校验会拒绝它。
     */
    public void invalidateAll(Set<DeviceRef> refs) {
        if (!available || refs.isEmpty()) {
            return;
        }
        Map<Integer, List<DeviceRef>> byBucket = new HashMap<>();
        for (DeviceRef ref : refs) {
            byBucket.computeIfAbsent(ref.bucket(MetadataRedisKeys.DEVICE_META_BUCKETS), k -> new ArrayList<>())
                .add(ref);
        }
        List<Request> requests = new ArrayList<>(byBucket.size());
        for (var entry : byBucket.entrySet()) {
            Request request = Request.cmd(Command.HDEL)
                .arg(MetadataRedisKeys.deviceMetaBucket(entry.getKey()));
            for (DeviceRef ref : entry.getValue()) {
                request.arg(ref.hashField());
            }
            requests.add(request);
        }
        try {
            commands.batch(requests);
        } catch (Exception e) {
            log.error("清理设备 L2 缓存失败: refs={}", refs.size(), e);
        }
    }

    public boolean isAvailable() {
        return available;
    }
}

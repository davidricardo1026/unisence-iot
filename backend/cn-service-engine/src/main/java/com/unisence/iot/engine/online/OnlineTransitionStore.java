package com.unisence.iot.engine.online;

import com.unisence.iot.redis.RedisCommands;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * transition stream 的读取与确认（device-online-state-design.md §10.5）。
 *
 * <p>stream <b>不是历史库</b>，而是<b>可靠交接队列</b>：Lua 在完成状态跳变时同脚本 {@code XADD}，
 * drainer 把 MySQL 与时序库都写成功之后才 {@code XACK + XDEL}。因此 engine 在任何一步宕机，
 * 未确认项都会留在 PEL 里，由别的实例 {@code XAUTOCLAIM} 接管 —— 这是内存队列给不了的保证。
 *
 * <p><b>禁止用 {@code MAXLEN} 裁掉未 ACK 项</b>：那等于把「可靠交接」退化成「尽力而为」。
 * 积压只能靠告警与背压处理，不能靠裁剪掩盖。
 */
@Slf4j
public class OnlineTransitionStore {

    /**
     * 一次读取的字段名，与 Lua 中的 XADD 保持一致。
     */
    private static final String FIELD_DEVICE_KEY = "deviceKey";
    private static final String FIELD_TARGET = "target";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_CHANGED_AT = "changedAt";

    private final RedisCommands commands;

    public OnlineTransitionStore(RedisCommands commands) {
        this.commands = commands;
    }

    /**
     * 幂等创建全部 256 个 shard 的消费组。
     *
     * <p>{@code MKSTREAM} 让 stream 不存在时一并创建；{@code BUSYGROUP} 表示别的实例已经建过，
     * 视为成功 —— 所有实例启动时都会跑这一步，报错才是异常。
     *
     * <p>起始位置固定为 {@code 0}：从头消费。用 {@code $} 会跳过创建组之前已经积压的项，
     * 那些跳变就永远不会被落库。
     */
    public void ensureConsumerGroups() {
        List<Request> batch = new ArrayList<>(OnlineShards.SHARD_COUNT);
        for (int shard = 0; shard < OnlineShards.SHARD_COUNT; shard++) {
            batch.add(Request.cmd(Command.XGROUP).arg("CREATE")
                          .arg(OnlineShards.transition(shard))
                          .arg(OnlineShards.TRANSITION_GROUP)
                          .arg("0").arg("MKSTREAM"));
        }
        // 逐条发送而不是 batch()：BUSYGROUP 是预期内的错误，pipeline 里一条失败会让整批的
        // 异常处理变得含混，而这只在启动时跑一次，256 次往返完全可以接受
        int created = 0;
        for (int shard = 0; shard < OnlineShards.SHARD_COUNT; shard++) {
            try {
                commands.send(batch.get(shard));
                created++;
            } catch (Throwable error) {
                // Vert.x Redis 的服务端错误使用 NoStackTraceThrowable（直接继承 Throwable，
                // 不是 Exception），这里只能在协议错误边界捕获 Throwable 后按错误码分类。
                if (!isBusyGroup(error)) {
                    throw new IllegalStateException(
                        "创建 transition 消费组失败: shard=" + shard, error);
                }
            }
        }
        log.info("transition 消费组就绪: group={} 新建={} 已存在={}",
                 OnlineShards.TRANSITION_GROUP, created, OnlineShards.SHARD_COUNT - created);
    }

    private static boolean isBusyGroup(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>一次 {@code XREADGROUP} 读取多个 shard</b> 的新项（{@code >}），返回按 shard 分组。
     *
     * <p><b>为什么必须是多流（契约 §10.5.1）</b>：{@code XREADGROUP} 的签名本就是
     * {@code STREAMS k1 k2 … > > …}。每 shard 各发一次阻塞读时，空 shard 会各自吃满一个
     * {@code BLOCK} 周期 —— 单实例持 256 shard、池 8 时空载一轮 32 秒，而那就是
     * 设备上下线跳变端到端延迟的<b>下界</b>。合并之后每轮阻塞调用数 ≤ 池容量，
     * 且任一 stream 的 {@code XADD} 会<b>立即</b>唤醒本次调用，延迟由处理速度决定。
     *
     * <p><b>{@code COUNT} 是每流上限而非本次调用的总上限</b>：单次响应最坏
     * {@code limit × shards.size()} 条。该语义变化已登记 {@code delivery-backlog.md} R10.1。
     *
     * <p><b>失败粒度也随之变粗</b>：消费组必须在列出的<b>每一个</b> stream 上存在，
     * 缺一个就整条命令 {@code NOGROUP} 失败，而不是只挂掉那一个 shard。
     * 因此捕获 {@code NOGROUP} 后重建消费组并重试一次 —— Redis 数据丢失后靠这条自愈。
     */
    public Map<Integer, List<PendingOnlineTransition>> readNew(List<Integer> shards, String consumerId,
                                                               int limit, long blockMs) {
        if (shards.isEmpty()) {
            return Map.of();
        }
        try {
            return sendReadNew(shards, consumerId, limit, blockMs);
        } catch (Throwable error) {
            if (!isNoGroup(error)) {
                throw error;
            }
            // 消费组缺失只可能来自 Redis 数据丢失（FLUSHDB、故障重建、AOF 回滚）。
            // 重建后重试一次；仍失败就让它抛出去，由 drainer 记错误并进入下一轮
            log.warn("transition 消费组缺失，重建后重试: shards={}", shards.size(), error);
            ensureConsumerGroups();
            return sendReadNew(shards, consumerId, limit, blockMs);
        }
    }

    private Map<Integer, List<PendingOnlineTransition>> sendReadNew(List<Integer> shards, String consumerId,
                                                                    int limit, long blockMs) {
        Request request = Request.cmd(Command.XREADGROUP)
            .arg("GROUP").arg(OnlineShards.TRANSITION_GROUP).arg(consumerId)
            .arg("COUNT").arg(limit)
            .arg("BLOCK").arg(blockMs)
            .arg("STREAMS");
        // key 与 id 必须分两段追加：STREAMS k1 k2 … > > …，不能交错
        Map<String, Integer> shardByKey = new HashMap<>(shards.size() * 2);
        for (Integer shard : shards) {
            String key = OnlineShards.transition(shard);
            shardByKey.put(key, shard);
            request.arg(key);
        }
        for (int i = 0; i < shards.size(); i++) {
            request.arg(">");
        }

        Response response = commands.send(request);
        if (response == null) {
            return Map.of();
        }
        // RESP3 返回 {streamKey: [[id, {k:v,...}], ...]}，RESP2 返回
        // [[streamKey, [[id, [k,v,...]], ...]], ...]；Vert.x 5 会把前者标成 Map，
        // 对 Map 调 get(int) 会直接抛出 “Multi is a Map”。
        // 单流时旧实现可以忽略 streamKey，多流下它是唯一的归属依据，必须解析
        Map<Integer, List<PendingOnlineTransition>> result = new LinkedHashMap<>();
        if (response.isMap()) {
            for (String streamKey : response.getKeys()) {
                collect(shardByKey.get(streamKey), streamKey, response.get(streamKey), result);
            }
        } else {
            for (Response stream : response) {
                if (stream == null || stream.size() < 2) {
                    continue;
                }
                String streamKey = stream.get(0).toString();
                collect(shardByKey.get(streamKey), streamKey, stream.get(1), result);
            }
        }
        return result;
    }

    /**
     * Redis 只会回我们请求过的 stream，因此 {@code shard == null} 说明响应与请求对不上，
     * 属于协议层异常。此时<b>丢弃并告警</b>而不是猜一个 shard —— 猜错会把跳变 ACK 到别的 shard 上。
     */
    private void collect(Integer shard, String streamKey, Response entries,
                         Map<Integer, List<PendingOnlineTransition>> result) {
        if (entries == null) {
            return;
        }
        if (shard == null) {
            log.error("XREADGROUP 返回了未请求的 stream，本组丢弃: streamKey={}", streamKey);
            return;
        }
        for (Response entry : entries) {
            PendingOnlineTransition parsed = parse(shard, entry);
            if (parsed != null) {
                result.computeIfAbsent(shard, key -> new ArrayList<>()).add(parsed);
            }
        }
    }

    private static boolean isNoGroup(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 接管本 shard 中空闲超过 {@code minIdleMs} 的 pending 项。
     *
     * <p>owner 变化（实例退出、rendezvous 重算）后，旧实例的 PEL 项不会自动回到新实例手里 ——
     * 必须靠 {@code XAUTOCLAIM} 显式接管，否则那些跳变会永久卡在 PEL 中。
     * 这是「进程崩溃不丢跳变」这条保证的最后一环。
     */
    public List<PendingOnlineTransition> claimStale(int shard, String consumerId, long minIdleMs, int limit) {
        Response response = commands.send(Request.cmd(Command.XAUTOCLAIM)
                                           .arg(OnlineShards.transition(shard))
                                           .arg(OnlineShards.TRANSITION_GROUP).arg(consumerId)
                                           .arg(minIdleMs).arg("0-0")
                                              .arg("COUNT").arg(limit));
        if (response == null || response.size() < 2) {
            return List.of();
        }
        // XAUTOCLAIM 返回 [nextCursor, [[id, [k,v,...]], ...], [deletedIds...]]
        Response entries = response.get(1);
        if (entries == null) {
            return List.of();
        }
        List<PendingOnlineTransition> result = new ArrayList<>(entries.size());
        for (Response entry : entries) {
            PendingOnlineTransition parsed = parse(shard, entry);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        if (!result.isEmpty()) {
            log.info("接管滞留的 transition: shard={} count={} minIdleMs={}", shard, result.size(), minIdleMs);
        }
        return result;
    }

    /**
     * 目标落点全部成功后确认。
     *
     * <p>{@code XACK} 把项移出 PEL，{@code XDEL} 把它从 stream 里删掉释放内存 —— 两者都需要：
     * 只 ACK 不 DEL 会让 stream 无限增长，只 DEL 不 ACK 会让 PEL 永远留着幽灵项。
     *
     * <p>同 shard 的项 pipeline 一次发出。
     */
    public void acknowledge(int shard, List<String> transitionIds) {
        if (transitionIds.isEmpty()) {
            return;
        }
        Request ack = Request.cmd(Command.XACK)
            .arg(OnlineShards.transition(shard)).arg(OnlineShards.TRANSITION_GROUP);
        Request del = Request.cmd(Command.XDEL).arg(OnlineShards.transition(shard));
        for (String id : transitionIds) {
            ack.arg(id);
            del.arg(id);
        }
        commands.batch(List.of(ack, del));
    }

    /**
     * 单 shard 待确认深度，供积压告警。
     */
    public long pendingCount(int shard) {
        try {
            Response response = commands.send(Request.cmd(Command.XPENDING)
                                               .arg(OnlineShards.transition(shard))
                                                  .arg(OnlineShards.TRANSITION_GROUP));
            if (response == null || response.size() == 0) {
                return 0;
            }
            Response count = response.get(0);
            return count == null ? 0 : count.toLong();
        } catch (Exception e) {
            log.warn("读取 transition 积压深度失败: shard={}", shard, e);
            return 0;
        }
    }

    /**
     * 解析一条 stream entry；结构损坏时跳过并告警，不让一条脏项挡住整批。
     */
    private PendingOnlineTransition parse(int shard, Response entry) {
        if (entry == null || entry.size() < 2) {
            return null;
        }
        String id = entry.get(0).toString();
        Response fields = entry.get(1);
        if (fields == null) {
            return null;
        }
        String deviceKey = null;
        String target = null;
        String reason = null;
        long changedAt = 0;
        if (fields.isMap()) {
            for (String name : fields.getKeys()) {
                Response raw = fields.get(name);
                String value = raw == null ? null : raw.toString();
                switch (name) {
                    case FIELD_DEVICE_KEY -> deviceKey = value;
                    case FIELD_TARGET -> target = value;
                    case FIELD_REASON -> reason = value;
                    case FIELD_CHANGED_AT -> changedAt = value == null ? 0 : Long.parseLong(value);
                    default -> {
                        // 未知字段：忽略即可，便于将来无损扩展载荷
                    }
                }
            }
        } else {
            for (int i = 0; i + 1 < fields.size(); i += 2) {
                String name = fields.get(i).toString();
                String value = fields.get(i + 1).toString();
                switch (name) {
                    case FIELD_DEVICE_KEY -> deviceKey = value;
                    case FIELD_TARGET -> target = value;
                    case FIELD_REASON -> reason = value;
                    case FIELD_CHANGED_AT -> changedAt = Long.parseLong(value);
                    default -> {
                        // 未知字段：忽略即可，便于将来无损扩展载荷
                    }
                }
            }
        }
        if (deviceKey == null || target == null) {
            log.error("transition 项结构损坏，已跳过: shard={} id={}", shard, id);
            return null;
        }
        try {
            return PendingOnlineTransition.of(shard, id, deviceKey,
                                              DeviceOnlineState.fromCode(Integer.parseInt(target)), reason, changedAt);
        } catch (RuntimeException e) {
            log.error("transition 项取值域外，已跳过: shard={} id={} target={}", shard, id, target, e);
            return null;
        }
    }
}

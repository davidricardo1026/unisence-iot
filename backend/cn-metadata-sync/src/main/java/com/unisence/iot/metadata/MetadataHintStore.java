package com.unisence.iot.metadata;

import com.unisence.iot.common.metadata.MetadataChangeHint;
import com.unisence.iot.common.metadata.MetadataRedisKeys;
import com.unisence.iot.redis.RedisCommands;
import com.unisence.iot.redis.RedisScript;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * Redis 侧的提示订阅与水位探测（metadata-sync-bus.md §五、§0.4）。
 *
 * <p>Redis 在这条链路上<b>不承担任何正确性</b>：Pub/Sub 是 at-most-once，head 是镜像。
 * 它们唯一的作用是把发现延迟从 30s（MySQL 反熵周期）压到毫秒级。因此本类的所有失败
 * 都只记日志、不向上传播 —— 让一次 Redis 抖动中断 engine 是完全不必要的。
 */
@Slf4j
public final class MetadataHintStore {

    private static final RedisScript LUA_MAX_AND_PUBLISH = RedisScript.of("""
        local current = redis.call('GET', KEYS[1]) or '0'
        local incoming = ARGV[1]
        if (#incoming > #current) or (#incoming == #current and incoming > current) then
          current = incoming
          redis.call('SET', KEYS[1], current)
        end
        redis.call('PUBLISH', ARGV[2],
          cjson.encode({schemaVersion = 1, desiredHead = current}))
        return current
                                                                              """);

    private final RedisCommands commands;
    private RedisConnection subscription;
    /**
     * 是否正在主动关闭。
     *
     * <p>{@code endHandler} 分不清「连接被对端断开」与「自己调了 close()」，两种情况都会触发。
     * 不区分的话，每次正常停机都会留下一条「订阅已断开，等待重连」——
     * 停机日志里最不该出现的就是这种伪告警：它把「干净停机」和「停机时 Redis 真的挂了」
     * 渲染成一模一样，事后无从分辨。
     */
    private final AtomicBoolean closing = new AtomicBoolean();

    public MetadataHintStore(RedisCommands commands) {
        this.commands = commands;
    }

    /**
     * 建立订阅。
     *
     * <p><b>顺序是硬要求：先确认订阅可接收，再读 head。</b>
     * 反过来「先读 head、后订阅」会在两步之间留下一个窗口 —— 期间发布的提示既不在
     * 已读到的 head 里，也没有被订阅接住，只能等 30s 反熵才发现。
     *
     * @param onHint 收到合法提示时的回调；调用方应转成 {@code observeDesiredHead}
     * @return 是否订阅成功
     */
    public boolean subscribe(LongConsumer onHint, Runnable onDisconnect) {
        // 重连路径也会走到这里，必须清掉上一轮的关闭标记
        closing.set(false);
        try {
            RedisConnection connection = commands.connect();
            connection.handler(response -> handleMessage(response, onHint));
            connection.exceptionHandler(e -> log.error("元数据提示订阅连接异常", e));
            connection.endHandler(ignored -> {
                if (closing.get()) {
                    log.info("元数据提示订阅已随停机关闭");
                    return;
                }
                log.warn("元数据提示订阅已断开，等待重连");
                onDisconnect.run();
            });
            connection.send(Request.cmd(Command.SUBSCRIBE).arg(MetadataRedisKeys.CHANGED_CHANNEL)).await();
            this.subscription = connection;
            log.info("已订阅元数据变更提示: channel={}", MetadataRedisKeys.CHANGED_CHANNEL);
            return true;
        } catch (Exception e) {
            log.error("订阅元数据变更提示失败，将依赖 head 探测与 MySQL 反熵", e);
            return false;
        }
    }

    /**
     * Pub/Sub 推送的消息形如 {@code ["message", channel, payload]}。
     */
    private void handleMessage(Response response, LongConsumer onHint) {
        try {
            if (response == null || response.size() < 3) {
                return;
            }
            if (!"message".equalsIgnoreCase(String.valueOf(response.get(0)))) {
                // subscribe/unsubscribe 的确认帧，忽略
                return;
            }
            String payload = String.valueOf(response.get(2));
            MetadataChangeHint hint = MetadataChangeHint.parse(payload);
            if (!hint.isSupportedSchema()) {
                // 未知 schemaVersion：拒绝解析并告警。猜字段含义可能把水位读错，
                // 而 MySQL 反熵反正还会发现新水位，不解析是安全的
                log.error("元数据提示 schemaVersion 未知，已拒绝解析: payload={}", payload);
                return;
            }
            onHint.accept(hint.desiredHead());
        } catch (Exception e) {
            log.error("解析元数据变更提示失败", e);
        }
    }

    /**
     * 读取 Redis head 镜像。
     *
     * <p>用<b>独立控制连接</b>（连接池）而不是订阅连接：处于订阅模式的连接只接受
     * SUBSCRIBE/UNSUBSCRIBE 等少数命令，在上面发 GET 会直接报错。
     *
     * @return 镜像水位；读取失败或键不存在返回 {@code -1}
     */
    public long probeHead() {
        try {
            Response response = commands.send(Request.cmd(Command.GET).arg(MetadataRedisKeys.HEAD));
            if (response == null) {
                return -1L;
            }
            return Long.parseLong(response.toString().trim());
        } catch (Exception e) {
            log.error("探测 Redis 元数据水位失败，本轮跳过", e);
            return -1L;
        }
    }

    public void publish(long committedSeq) {
        try {
            LUA_MAX_AND_PUBLISH.execute(
                commands,
                List.of(MetadataRedisKeys.HEAD),
                List.of(Long.toString(committedSeq), MetadataRedisKeys.CHANGED_CHANNEL));
        } catch (Exception error) {
            log.error("发布元数据变更提示失败，将由 MySQL 反熵兜底: committedSeq={}", committedSeq, error);
        }
    }

    public void close() {
        if (subscription == null) {
            return;
        }
        closing.set(true);
        try {
            subscription.close().await();
        } catch (Exception e) {
            log.warn("关闭元数据提示订阅失败", e);
        }
    }
}

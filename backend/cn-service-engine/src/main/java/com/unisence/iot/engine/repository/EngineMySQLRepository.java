package com.unisence.iot.engine.repository;

import com.unisence.iot.common.batch.BatchResult;
import com.unisence.iot.engine.online.DeviceOnlineState;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 访问（engine-runtime-io.md §四）。
 *
 * <p>元数据读取<b>不在此处</b>：产品、物模型、规则与设备目录一律由
 * {@code com.unisence.iot.engine.metadata} 的四个 loader 在<b>同一个只读一致性快照</b>内加载
 * （metadata-sync-bus.md §7.3）。这里保留的只是运行态写入 —— 把两者混在一个仓储里，
 * 迟早会有人用池连接去读元数据，从而绕开一致性快照。
 *
 * <p>使用 {@code vertx-mysql-client}（Netty 原生协议实现，非 JDBC），配 {@code Future.await()}
 * 写成顺序代码 —— 既是非阻塞 I/O，又没有回调链，且**不存在 Connector/J 的虚拟线程 pinning 风险**。
 *
 * <p>调用方必须运行在虚拟线程上（实测 {@code await()} 在普通虚拟线程上可用，不要求 Vert.x context）。
 */
@Slf4j
public class EngineMySQLRepository {

    private final Pool pool;

    public EngineMySQLRepository(Pool pool) {
        this.pool = pool;
    }


    /**
     * 批量写设备状态跳变（device-online-state-design.md §7.1）。
     *
     * <p>用 {@code executeBatch} 而非「按状态分组 + IN 列表」：前者是<b>一次往返 + 一条固定 SQL</b>，
     * 能命中 {@code cachePreparedStatements}；后者的 SQL 长度随批大小变化，会让预编译缓存持续失效。
     * 拆成两条语句只因 {@code last_online_at} 仅在上线时推进。
     *
     * <p><b>fencing</b>：{@code WHERE (status_event_ms, status_event_seq) < (?, ?)} 让重放或
     * 并发较晚完成的旧 transition 影响 0 行，无法覆盖更新的状态。这一条是批量重试能够安全存在的前提 ——
     * 没有它，失败重试与乱序投递都会把旧状态写回去。
     *
     * <p><b>影响 0 行不是失败</b>：它意味着「已应用」或「已有更新项」，按契约不得当作失败重试。
     *
     * <p>调用方保证批内已按 deviceKey 合并。
     *
     * @return 三分结果；整批 I/O 异常归 retryable，数据类异常归 poison
     */
    public BatchResult<DeviceStatusUpdate> updateDeviceStatusBatch(List<DeviceStatusUpdate> updates) {
        if (updates.isEmpty()) {
            return BatchResult.allSucceeded(List.of());
        }
        List<DeviceStatusUpdate> onlineItems = new ArrayList<>();
        List<DeviceStatusUpdate> otherItems = new ArrayList<>();
        List<Tuple> online = new ArrayList<>();
        List<Tuple> others = new ArrayList<>();
        for (DeviceStatusUpdate update : updates) {
            if (update.target() == DeviceOnlineState.ONLINE) {
                onlineItems.add(update);
                online.add(Tuple.of(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(update.changedAtMs()), ZoneId.systemDefault()),
                    update.streamMs(), update.streamSeq(),
                    update.deviceId(),
                    update.streamMs(), update.streamMs(), update.streamSeq()));
            } else {
                otherItems.add(update);
                others.add(Tuple.of(
                    update.target().code(), update.streamMs(), update.streamSeq(),
                    update.deviceId(),
                    update.streamMs(), update.streamMs(), update.streamSeq()));
            }
        }
        BatchResult<DeviceStatusUpdate> result = BatchResult.allSucceeded(List.of());
        result = result.merge(execute(SQL_STATUS_ONLINE, online, onlineItems));
        result = result.merge(execute(SQL_STATUS_OTHER, others, otherItems));
        log.debug("设备状态批量落库: 上线={} 其它={} 成功={} 待重试={} 毒药={}",
                  online.size(), others.size(),
                  result.succeeded().size(), result.retryable().size(), result.poison().size());
        return result;
    }

    /**
     * fencing 条件写成 {@code ms < ? OR (ms = ? AND seq < ?)} 而不是行值比较 {@code (a,b) < (?,?)}：
     * 二者语义等价，但前者能让优化器正常使用索引，后者在部分 MySQL 版本上会退化。
     */
    private static final String SQL_STATUS_ONLINE = """
        UPDATE us_iot_device
           SET status = 1, last_online_at = ?, status_event_ms = ?, status_event_seq = ?
         WHERE device_id = ? AND deleted = 0
           AND (status_event_ms < ? OR (status_event_ms = ? AND status_event_seq < ?))
        """;

    private static final String SQL_STATUS_OTHER = """
        UPDATE us_iot_device
           SET status = ?, status_event_ms = ?, status_event_seq = ?
         WHERE device_id = ? AND deleted = 0
           AND (status_event_ms < ? OR (status_event_ms = ? AND status_event_seq < ?))
        """;

    private <T> BatchResult<T> execute(String sql, List<Tuple> params, List<T> items) {
        if (params.isEmpty()) {
            return BatchResult.allSucceeded(List.of());
        }
        try {
            pool.preparedQuery(sql).executeBatch(params).await();
            // 影响 0 行 = 已应用或已有更新项，按契约同样算成功，不重试
            return BatchResult.allSucceeded(items);
        } catch (Exception e) {
            if (isPoison(e)) {
                log.error("设备状态批量落库遇确定性错误，不再重试: size={}", items.size(), e);
                return BatchResult.allPoison(items);
            }
            log.error("设备状态批量落库失败，待重试: size={}", items.size(), e);
            return BatchResult.allRetryable(items);
        }
    }

    /**
     * 区分「重试有用」与「重试一万次也没用」。
     *
     * <p>SQLSTATE 前两位是类别码：{@code 22}=数据异常（越界、类型不符）、
     * {@code 23}=完整性约束冲突、{@code 42}=语法或访问规则错误。这三类重试多少次结果都一样，
     * 必须分流成毒药，否则一条脏数据会把整条链路永久堵死。
     */
    private static boolean isPoison(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.vertx.mysqlclient.MySQLException mysql) {
                String state = mysql.getSqlState();
                if (state != null && state.length() >= 2) {
                    String category = state.substring(0, 2);
                    return "22".equals(category) || "23".equals(category) || "42".equals(category);
                }
            }
        }
        return false;
    }
}

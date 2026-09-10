package com.unisence.iot.engine.metadata;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

/**
 * Vert.x 连接池账本与操作系统实际 socket 数的背离观测
 * （metadata-sync-mysql-connection-exhaustion-incident.md §5.2 第 5 条）。
 *
 * <p><b>为什么需要它。</b>Vert.x 的 {@code SimpleConnectionPool} 把「移出槽位」和「物理关闭」
 * 分成两步：{@code Remove.execute()} 只把 {@code slot.connection} 置空并收缩 {@code size}，
 * 真正的关闭由 {@code SqlConnectionPool.evict()} 用一个<b>没有任何 handler 的裸 promise</b>
 * 补发。连接一旦离开账本就不再受 {@code pool-max-size} 约束，而关闭失败没有任何一层会知道 ——
 * 现场因此能出现「账本 16 条、实际 147 条」而全程无告警。
 *
 * <p><b>MySQL 与 Redis 一起观测。</b>两者共用同一个 {@code io.vertx.core.internal.pool.ConnectionPool}
 * 与同一条 {@code SAME_EVENT_LOOP_SELECTOR} 复用判据（§四bis.4），故障模式相同，
 * 没有理由只盯 MySQL。一次 {@code /proc} 扫描同时给出两者的读数。
 *
 * <p><b>判据不是调优阈值。</b>engine 自己的 socket 在设计上<b>永远</b>不可能超过对应池的
 * {@code pool-max-size}：超出的部分按定义就是脱离账本的连接。因此告警线直接取配置值本身，
 * 不含任何经验系数，也不属于 R10.1 待压测定值项。
 *
 * <p>非 Linux 或 {@code /proc} 不可读时自动停用并只提示一次 —— 观测缺失不能影响业务本身。
 */
@Slf4j
public final class VertxPoolSocketLedger {

    /**
     * 上一轮各目标的 socket 数，用于区分「稳定超限」与「持续增长」。
     */
    private final java.util.Map<String, Integer> lastSockets = new java.util.HashMap<>();

    private static final Path PROC_FD = Path.of("/proc/self/fd");
    private static final List<Path> PROC_TCP =
        List.of(Path.of("/proc/self/net/tcp"), Path.of("/proc/self/net/tcp6"));

    private static final String SOCKET_LINK_PREFIX = "socket:[";
    private static final int REMOTE_ADDRESS_COLUMN = 2;
    private static final int INODE_COLUMN = 9;
    /**
     * {@code rem_address} 的端口部分固定 4 位十六进制。
     */
    private static final int PORT_HEX_LENGTH = 4;

    /**
     * 一个观测目标。
     *
     * @param name        日志里的名字，如 {@code MySQL} / {@code Redis}
     * @param port        远端端口
     * @param poolMaxSize 该池配置的上限，即告警线
     * @param ledgerSize  池账本读数；<b>可为 {@code null}</b> ——
     *                    {@code io.vertx.redis.client.Redis} 没有暴露任何池大小访问器，
     *                    Redis 目标只能拿到实际 socket 数。账本缺失只影响日志里的对照字段，
     *                    不影响告警判定（判定用的是 {@code poolMaxSize}）
     */
    public record Target(String name, int port, int poolMaxSize, IntSupplier ledgerSize) {

        private String portHex() {
            return String.format("%04X", port);
        }
    }

    private final List<Target> targets;

    /**
     * 探测不可用后置位，避免每个周期都重复刷同一条告警。
     */
    private boolean disabled;

    public VertxPoolSocketLedger(List<Target> targets) {
        this.targets = List.copyOf(targets);
    }

    /**
     * 比对一次。发现背离只告警，不做任何修复动作 —— 连接生命周期在 Vert.x 池内部，
     * 这里贸然干预只会把一个可诊断的问题变成两个。
     */
    public void check() {
        if (disabled) {
            return;
        }
        Map<String, Integer> byPort;
        try {
            byPort = countOwnSocketsByPort();
        } catch (IOException | RuntimeException e) {
            disabled = true;
            log.warn("Vert.x 连接账本观测不可用，已停用（不影响业务）", e);
            return;
        }

        int total = 0;
        for (Target target : targets) {
            total += byPort.getOrDefault(target.portHex(), 0);
        }
        if (total == 0) {
            // engine 运行期必然持有 MySQL 连接，全部目标合计为 0 只可能是解析失效。
            // 不在此处报出来，本观测就会永远返回「未超限」，
            // 让「测量失败」与「没有泄漏」变得无法区分
            disabled = true;
            log.warn("Vert.x 连接账本观测结果不可信，已停用: 全部目标实际 socket 数合计为 0");
            return;
        }

        for (Target target : targets) {
            int sockets = byPort.getOrDefault(target.portHex(), 0);
            String ledger = target.ledgerSize() == null
                ? "不可读" : String.valueOf(target.ledgerSize().getAsInt());
            if (sockets > target.poolMaxSize()) {
                // 「超限」与「持续增长」是两件事，必须分开报（2026-08-09 实测：
                // MySQL 恒定在 实际24/上限20 不动，而原文案称「持续增长会耗尽服务端连接」——
                // 判据与文案不符，且每轮重复同一条，是典型的告警疲劳源）
                int previous = lastSockets.getOrDefault(target.name(), -1);
                lastSockets.put(target.name(), sockets);
                if (previous >= 0 && sockets > previous) {
                    log.error("{} 连接数超出上限**且仍在增长**: 实际socket={}（上轮 {}）池账本={} 池上限={}。"
                                  + "这是真正会耗尽服务端连接的形态，须立即排查脱离池的创建点",
                              target.name(), sockets, previous, ledger, target.poolMaxSize());
                } else if (previous != sockets) {
                    // 稳定超限：确实有脱离池的连接，但不构成耗尽风险。
                    // 只在数值变化时报一次，避免每轮刷同一条
                    log.warn("{} 连接数超出上限但**保持稳定**: 实际socket={} 池账本={} 池上限={}。"
                                 + "存在脱离池的连接（如客户端自建的探测/订阅连接），"
                                 + "不构成耗尽风险；数值变化时才会再次报出",
                             target.name(), sockets, ledger, target.poolMaxSize());
                }
            } else if (log.isDebugEnabled()) {
                lastSockets.remove(target.name());
                log.debug("{} 连接账本一致: 实际socket={} 池账本={} 池上限={}",
                          target.name(), sockets, ledger, target.poolMaxSize());
            }
        }
    }

    /**
     * 一次扫描统计本进程各远端端口上的 socket 数。
     *
     * <p><b>必须按 inode 反查。</b>{@code /proc/self/net/tcp} 是<b>网络命名空间</b>级视图而非
     * 进程级：直接统计会把同一容器/主机里其它进程（例如 admin）的连接算进来，判据失去意义。
     *
     * <p><b>刻意不按 TCP 状态过滤。</b>只统计 {@code ESTABLISHED} 会系统性漏掉正要抓的那批 socket：
     * 泄漏连接的典型形态恰恰是 {@code CLOSE_WAIT} / {@code FIN_WAIT} —— 服务端已关闭，
     * 而本进程的 fd 从未关掉。实测阳性对照中，制造 {@code CLOSE_WAIT} 后「只数 ESTABLISHED」
     * 返回 0，与「没有泄漏」完全无法区分。远端端口非零本身已排除 {@code LISTEN}
     * （监听行的 {@code rem_address} 恒为 {@code 00000000:0000}）。
     *
     * @return 远端端口（4 位大写十六进制）→ 本进程持有的 socket 数
     */
    private Map<String, Integer> countOwnSocketsByPort() throws IOException {
        Set<String> ownInodes = ownSocketInodes();
        Map<String, Integer> counts = new HashMap<>();
        if (ownInodes.isEmpty()) {
            return counts;
        }
        Set<String> wanted = new HashSet<>();
        for (Target target : targets) {
            wanted.add(target.portHex());
        }
        for (Path table : PROC_TCP) {
            if (!Files.isReadable(table)) {
                // tcp6 在纯 IPv4 内核上可能不存在，不是错误
                continue;
            }
            try (Stream<String> lines = Files.lines(table)) {
                lines.forEach(line -> tally(line, ownInodes, wanted, counts));
            }
        }
        return counts;
    }

    private void tally(String line, Set<String> ownInodes, Set<String> wanted, Map<String, Integer> counts) {
        String[] columns = line.trim().split("\\s+");
        if (columns.length <= INODE_COLUMN) {
            // 表头行
            return;
        }
        String remote = columns[REMOTE_ADDRESS_COLUMN];
        int separator = remote.lastIndexOf(':');
        if (separator < 0 || remote.length() - separator - 1 < PORT_HEX_LENGTH) {
            return;
        }
        String portHex = remote.substring(separator + 1, separator + 1 + PORT_HEX_LENGTH)
            .toUpperCase(java.util.Locale.ROOT);
        if (!wanted.contains(portHex) || !ownInodes.contains(columns[INODE_COLUMN])) {
            return;
        }
        counts.merge(portHex, 1, Integer::sum);
    }

    /**
     * 从 {@code /proc/self/fd} 读出本进程持有的全部 socket inode。
     */
    private static Set<String> ownSocketInodes() throws IOException {
        Set<String> inodes = new HashSet<>();
        try (Stream<Path> fds = Files.list(PROC_FD)) {
            fds.forEach(fd -> {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.startsWith(SOCKET_LINK_PREFIX) && target.endsWith("]")) {
                        inodes.add(target.substring(SOCKET_LINK_PREFIX.length(), target.length() - 1));
                    }
                } catch (IOException e) {
                    // fd 在遍历过程中被关闭是常态，不是故障
                    log.trace("读取 fd 链接失败: fd={}", fd, e);
                }
            });
        }
        return inodes;
    }
}

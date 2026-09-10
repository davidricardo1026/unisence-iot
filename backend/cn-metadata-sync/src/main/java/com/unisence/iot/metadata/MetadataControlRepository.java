package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.metadata.MetadataScope;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 控制面读取：提交水位与变更目录（metadata-sync-bus.md §三、§7.3）。
 *
 * <p>engine 侧的 MySQL 访问一律走 {@code vertx-mysql-client} + {@code Future.await()}：
 * 协议级非阻塞 I/O 配同步写法，且不存在 Connector/J 的虚拟线程 pinning 风险。
 * 调用方必须运行在虚拟线程上。
 */
@Slf4j
public final class MetadataControlRepository {

    private static final String SELECT_HEAD =
        "SELECT committed_seq FROM us_sys_metadata_head WHERE head_code = 'MAIN' AND deleted = 0";

    private static final String SELECT_CHANGES = """
        SELECT commit_seq, meta_key, scope_id
          FROM us_sys_metadata_change
         WHERE commit_seq > ? AND commit_seq <= ?
         ORDER BY commit_seq, change_id
        """;

    private static final String SELECT_MIN_CHANGE_SEQ =
        "SELECT MIN(commit_seq) AS min_seq FROM us_sys_metadata_change";

    private final Pool pool;

    public MetadataControlRepository(Pool pool) {
        this.pool = pool;
    }

    /**
     * 反熵路径：直接读权威水位。Redis 整体不可用时这是唯一仍然正确的发现方式。
     */
    public long readCommittedHead() {
        RowSet<Row> rows = pool.preparedQuery(SELECT_HEAD).execute().await();
        for (Row row : rows) {
            return row.getLong("committed_seq");
        }
        throw new IllegalStateException("us_sys_metadata_head 的 MAIN 单例行缺失");
    }

    /**
     * 在一个只读一致性快照内执行整轮收敛。
     *
     * <p>隔离级别与快照时点必须由这里统一设定：
     * <ul>
     *   <li>{@code REPEATABLE READ} —— 整个事务共享同一个 read view，产品、属性、事件
     *       一定来自同一提交时点。用 {@code READ COMMITTED} 分多条 SELECT 会拼出
     *       数据库里从未存在过的组合。</li>
     *   <li>{@code WITH CONSISTENT SNAPSHOT} —— 快照在 {@code START TRANSACTION} 的瞬间确定，
     *       而不是等到第一条 SELECT。少了它，「读 head」与「读业务表」之间仍有窗口。</li>
     *   <li>{@code READ ONLY} —— 让 InnoDB 跳过事务 ID 分配等写路径开销。</li>
     * </ul>
     *
     * <p>{@code committedHead} 也在同一快照内读出，因此候选根的水位与它包含的数据严格对应。
     */
    public <T> T inConsistentSnapshot(Function<MetadataReadView, T> work) {
        SqlConnection connection = pool.getConnection().await();
        try {
            connection.query("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ").execute().await();
            connection.query("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY").execute().await();
            boolean committed = false;
            try {
                long head = readHead(connection);
                MutableReadView readView = new MutableReadView(connection, head);
                T result = work.apply(readView);
                connection.query("COMMIT").execute().await();
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    rollbackQuietly(connection);
                }
            }
        } finally {
            connection.close().await();
        }
    }

    private static long readHead(SqlConnection connection) {
        RowSet<Row> rows = connection.preparedQuery(SELECT_HEAD).execute().await();
        for (Row row : rows) {
            return row.getLong("committed_seq");
        }
        throw new IllegalStateException("us_sys_metadata_head 的 MAIN 单例行缺失");
    }

    private static void rollbackQuietly(SqlConnection connection) {
        try {
            connection.query("ROLLBACK").execute().await();
        } catch (Exception e) {
            // 连接紧接着就会关闭，回滚失败不影响正确性；但吞掉会掩盖连接层问题
            log.warn("回滚只读一致性快照失败", e);
        }
    }

    /**
     * 读取 {@code (fromExclusive, toInclusive]} 的变化范围。
     *
     * @return 去重后的 scope；{@code null} 表示<b>日志有缺口</b>，调用方必须走全量重建，
     * 禁止猜测缺失范围
     */
    public Set<MetadataScope> readChanges(MetadataReadView readView, long fromExclusive, long toInclusive) {
        RowSet<Row> rows = readView.connection()
            .preparedQuery(SELECT_CHANGES)
            .execute(Tuple.of(fromExclusive, toInclusive))
            .await();

        Set<MetadataScope> scopes = new LinkedHashSet<>();
        List<Long> seenSeqs = new ArrayList<>();
        for (Row row : rows) {
            seenSeqs.add(row.getLong("commit_seq"));
            String metaKey = row.getString("meta_key");
            try {
                scopes.add(new MetadataScope(MetaKeyEnum.fromKey(metaKey), row.getLong("scope_id")));
            } catch (IllegalArgumentException e) {
                // 未知 meta_key：本实例版本比数据里的取值域旧。绝不能跳过该行当作「无变化」——
                // 那会让 appliedHead 越过一个自己没能力应用的水位，且永远不会再回来补
                log.error("变更目录出现未知 meta_key，拒绝越过该水位: metaKey={} commitSeq={}",
                          metaKey, row.getLong("commit_seq"), e);
                throw new UnknownMetaKeyException(metaKey, e);
            }
        }
        if (hasGap(readView, fromExclusive, toInclusive, seenSeqs)) {
            return null;
        }
        return scopes;
    }

    /**
     * 判断变更日志是否已被清理出缺口。
     *
     * <p>依据是清理器的不变量：每次分配 head 必写至少一个 scope，且清理只删连续前缀。
     * 因此只要「日志中最小的 commit_seq 已经大于 {@code appliedHead + 1}」，
     * 中间那段就已经被删掉了，增量路径不可能补全。
     */
    private boolean hasGap(MetadataReadView readView, long fromExclusive, long toInclusive, List<Long> seenSeqs) {
        if (fromExclusive >= toInclusive) {
            return false;
        }
        if (!seenSeqs.isEmpty() && seenSeqs.get(0) == fromExclusive + 1) {
            return false;
        }
        RowSet<Row> rows = readView.connection().preparedQuery(SELECT_MIN_CHANGE_SEQ).execute().await();
        Long minSeq = null;
        for (Row row : rows) {
            minSeq = row.getLong("min_seq");
        }
        if (minSeq == null) {
            // 日志为空但水位仍在前进：说明该区间整段被清理掉了
            log.warn("变更目录为空但仍落后，判定为日志缺口: appliedHead={} committedHead={}",
                     fromExclusive, toInclusive);
            return true;
        }
        if (minSeq > fromExclusive + 1) {
            log.warn("变更目录已被清理出缺口，转全量重建: minCommitSeq={} appliedHead={} committedHead={}",
                     minSeq, fromExclusive, toInclusive);
            return true;
        }
        return false;
    }

    /**
     * 未知元数据域；触发 DEGRADED 而不是静默跳过。
     */
    public static final class UnknownMetaKeyException extends RuntimeException {
        public UnknownMetaKeyException(String metaKey, Throwable cause) {
            super("变更目录含未知 meta_key: " + metaKey, cause);
        }
    }

    /**
     * 一次收敛内共享的读视图；候选产品映射在产品域构建完成后由收敛器填入。
     */
    private static final class MutableReadView implements MetadataReadView {

        private final SqlConnection connection;
        private final long committedHead;
        private java.util.Map<Long, ProductRuntimeMeta> candidateProducts = java.util.Map.of();

        private MutableReadView(SqlConnection connection, long committedHead) {
            this.connection = connection;
            this.committedHead = committedHead;
        }

        @Override
        public SqlConnection connection() {
            return connection;
        }

        @Override
        public long committedHead() {
            return committedHead;
        }

        @Override
        public java.util.Map<Long, ProductRuntimeMeta> candidateProducts() {
            return candidateProducts;
        }
    }

    /**
     * 供收敛器在固定构建顺序中填入候选产品映射。
     */
    public static void publishCandidateProducts(MetadataReadView readView,
                                                java.util.Map<Long, ProductRuntimeMeta> products) {
        if (readView instanceof MutableReadView view) {
            view.candidateProducts = products;
            return;
        }
        throw new IllegalArgumentException("未知的 MetadataReadView 实现: " + readView.getClass());
    }
}

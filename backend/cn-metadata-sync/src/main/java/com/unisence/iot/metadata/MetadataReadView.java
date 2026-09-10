package com.unisence.iot.metadata;

import io.vertx.sqlclient.SqlConnection;

/**
 * 一次收敛所使用的<b>唯一</b>只读一致性快照（metadata-sync-bus.md §7.3）。
 *
 * <p>包装当前 {@code REPEATABLE READ / READ ONLY / WITH CONSISTENT SNAPSHOT} 事务的那一个连接。
 * loader <b>只能</b>通过 {@link #connection()} 查询，禁止回到连接池另取连接 ——
 * 那会拿到另一个 read view，于是产品、属性和事件可能来自不同提交时点，
 * 拼出一个数据库里从未存在过的组合。
 *
 * <p>同理，{@code committedHead} 也必须在这个快照里读出：先用别的连接读 head、再开事务读业务表，
 * 两步之间的提交会永久漏读。
 */
public interface MetadataReadView {

    /**
     * 本次收敛的唯一连接。
     */
    SqlConnection connection();

    /**
     * 该一致性快照可见的权威提交水位；候选根的 {@code appliedHead} 就取它。
     */
    long committedHead();

    /**
     * 本轮已构建好的候选产品映射（{@code productId → 产品}）。
     *
     * <p>构建顺序固定为「产品 → 设备版本目录 → 物模型 → 规则」，因此设备与规则 loader 运行时
     * 这份映射一定已就绪。设备 loader 靠它把 {@code product_id} 解析成 {@code productKey} ——
     * 这正是「禁止逐设备查询、也禁止全量分页时重复 join 产品表」的实现方式：
     * 百万行扫描每页都 join 一次产品表是纯粹的浪费。
     *
     * <p>它<b>不是</b> loader 自己持有的缓存引用：生命周期只有本次构建这一轮。
     */
    java.util.Map<Long, ProductRuntimeMeta> candidateProducts();
}

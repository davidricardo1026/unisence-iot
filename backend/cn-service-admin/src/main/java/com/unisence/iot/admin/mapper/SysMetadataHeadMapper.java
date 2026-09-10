package com.unisence.iot.admin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 提交水位分配（metadata-sync-bus.md §3.4）。
 *
 * <p>刻意<b>不</b>建 {@code SysMetadataHead} 实体 + MyBatis-Plus 通用 CRUD：水位推进不是普通
 * 「读-改-写」，而是必须由数据库在<b>同一条 UPDATE 内</b>完成自增与取值，才能让「取号顺序 == 提交顺序」
 * 成立。用实体先 select 再 update 会引入应用侧的读-改-写窗口，两个并发事务可能取到同一个号。
 * 因此本接口是纯注解 Mapper，不继承 {@code BaseMapper}。
 */
public interface SysMetadataHeadMapper {

    /**
     * 推进单例行水位。
     *
     * <p>{@code LAST_INSERT_ID(expr)} 把新值写入<b>连接级</b>的 last-insert-id 寄存器，
     * 使得紧随其后的 {@code SELECT LAST_INSERT_ID()} 能在并发下安全地取回<b>本连接</b>刚分配的值。
     * 这是 MySQL 官方文档给出的并发安全序列生成手法。
     *
     * <p>单例行的写锁一直持有到事务结束，因此不同 admin/engine 实例取得水位的顺序
     * 就是它们提交的顺序 —— 这正是 engine 可以安全地把 {@code commit_seq} 当游标的前提。
     *
     * @return 受影响行数；必须为 1，为 0 说明 MAIN 行缺失（初始化脚本未执行）
     */
    @Update("""
        UPDATE us_sys_metadata_head
           SET committed_seq = LAST_INSERT_ID(committed_seq + 1),
               update_by     = #{operatorId},
               update_time   = CURRENT_TIMESTAMP,
               version       = version + 1
         WHERE head_code = 'MAIN' AND deleted = 0
        """)
    int advanceHead(@Param("operatorId") Long operatorId);

    /**
     * 取回<b>本连接</b>刚分配的水位。
     *
     * <p>必须与 {@link #advanceHead} 使用同一个 Spring 事务绑定的连接 ——
     * 两条语句之间不得穿插任何会另取连接的操作。
     */
    @Select("SELECT LAST_INSERT_ID()")
    long lastAllocatedSeq();

    /**
     * 权威提交水位。engine 反熵与 admin 状态接口都以此为准，Redis 镜像只作诊断。
     */
    @Select("SELECT committed_seq FROM us_sys_metadata_head WHERE head_code = 'MAIN' AND deleted = 0")
    Long selectCommittedSeq();
}

package com.unisence.iot.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unisence.iot.admin.entity.SysMetadataChange;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 变更目录访问。写入走 MyBatis-Plus 的 {@code insert}（由 {@code MetadataChangeRecorder} 批量调用），
 * 这里只补两个清理专用查询。
 */
public interface SysMetadataChangeMapper extends BaseMapper<SysMetadataChange> {

    /**
     * 取「保留期之外」的第 N 个 commit_seq，作为本轮清理的<b>右边界（含）</b>。
     *
     * <p>先定位边界再按 {@code commit_seq <=} 删除，而不是直接 {@code DELETE ... LIMIT n}：
     * 后者会把同一个提交的多个 scope 行删掉一部分，engine 就会看到「有 commit_seq 但 scope 不全」
     * 的半截提交，从而静默漏刷新某个聚合根。按完整批次的连续前缀清理才安全。
     *
     * @return 边界 commit_seq；无可清理批次时返回 {@code null}
     */
    @Select("""
        SELECT commit_seq
          FROM (
                SELECT DISTINCT commit_seq
                  FROM us_sys_metadata_change
                 WHERE create_time < #{before}
                 ORDER BY commit_seq
                 LIMIT #{batchSize}
               ) t
         ORDER BY commit_seq DESC
         LIMIT 1
        """)
    Long selectCleanupBoundary(@Param("before") java.time.LocalDateTime before,
                               @Param("batchSize") int batchSize);

    /**
     * 删除连续前缀 {@code commit_seq <= boundary} 的全部行。
     */
    @Delete("DELETE FROM us_sys_metadata_change WHERE commit_seq <= #{boundary}")
    int deleteUpTo(@Param("boundary") long boundary);
}

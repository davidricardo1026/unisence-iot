package com.unisence.iot.admin.metadata;

import com.unisence.iot.admin.mapper.SysMetadataChangeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 变更目录的有界清理（metadata-sync-bus.md §3.5）。
 *
 * <p>只删<b>连续前缀</b>，且以完整提交批次为单位。这个约束不是洁癖：engine 判定「日志有缺口」
 * 靠的正是 {@code MIN(commit_seq) > appliedHead + 1}，前提是保留下来的一定是连续后缀。
 * 一旦从中间挖掉几个 commit_seq，engine 会误以为日志完整而漏刷新那些范围。
 *
 * <p>同理禁止用行数 {@code LIMIT} 删除：那会把同一提交的多个 scope 行删掉一部分，
 * 让 engine 看到半截提交。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataChangeCleaner {

    private final SysMetadataChangeMapper changeMapper;
    private final MetadataSyncProperties properties;

    /**
     * 每小时清理一次。
     *
     * <p>保留期必须大于允许的 engine 最长连续失联时间 —— 失联超过保留期的实例会发现缺口，
     * 只能走全量一致性重建（这是安全但昂贵的兜底，不是常态）。
     */
    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void cleanup() {
        LocalDateTime before = LocalDateTime.now().minusDays(properties.getChangeRetentionDays());
        try {
            Long boundary = changeMapper.selectCleanupBoundary(before, properties.getCleanupCommitBatchSize());
            if (boundary == null) {
                return;
            }
            int deleted = changeMapper.deleteUpTo(boundary);
            log.info("元数据变更目录已清理: boundaryCommitSeq={} deletedRows={} retentionDays={}",
                     boundary, deleted, properties.getChangeRetentionDays());
        } catch (Exception e) {
            // 清理失败只是磁盘占用增长，不影响同步正确性；下一轮继续
            log.error("元数据变更目录清理失败: retentionDays={}", properties.getChangeRetentionDays(), e);
        }
    }
}

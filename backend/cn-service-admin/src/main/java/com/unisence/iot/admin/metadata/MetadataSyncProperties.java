package com.unisence.iot.admin.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 元数据同步总线的 admin 侧配置（metadata-sync-bus.md §十二）。
 *
 * <p>Redis 固定键不在此配置 —— 见 {@code MetadataRedisKeys} 的说明。
 */
@Data
@ConfigurationProperties(prefix = "app.metadata.sync")
public class MetadataSyncProperties {

    /**
     * 变更日志保留天数。
     *
     * <p><b>必须大于允许的 engine 最长连续失联时间</b>：实例失联超过保留期后，
     * {@code (appliedHead, committedHead]} 会出现缺口，只能走全量一致性重建。
     */
    private int changeRetentionDays = 7;

    /**
     * 单次清理的 commit_seq 批次上限。
     *
     * <p>按<b>完整提交批次</b>清理而不是按行数 {@code LIMIT}：后者会把同一次提交的多个 scope 行
     * 删掉一部分，让 engine 读到「有这个 commit_seq 但 scope 不全」的半截提交，
     * 从而静默漏刷新某个聚合根。
     */
    private int cleanupCommitBatchSize = 500;

    /**
     * 单次提交的去重 scope 数超过该阈值时，改记 {@code scope_id=0} 全域重建。
     *
     * <p>批量导入十万台设备若逐条记 scope，会让变更目录本身成为瓶颈，
     * 且 engine 增量路径的收益也已消失 —— 此时流式重建紧凑版本目录反而更快。
     */
    private int fullRebuildScopeThreshold = 500;

    /**
     * 启动即校验，越界直接拒绝启动，不带病运行。
     */
    public void validate() {
        if (changeRetentionDays <= 0) {
            throw new IllegalStateException("app.metadata.sync.change-retention-days 必须为正数: " + changeRetentionDays);
        }
        if (cleanupCommitBatchSize <= 0) {
            throw new IllegalStateException(
                "app.metadata.sync.cleanup-commit-batch-size 必须为正数: " + cleanupCommitBatchSize);
        }
        if (fullRebuildScopeThreshold <= 0) {
            throw new IllegalStateException(
                "app.metadata.sync.full-rebuild-scope-threshold 必须为正数: " + fullRebuildScopeThreshold);
        }
    }
}

package com.unisence.iot.admin.metadata.vo;

import java.util.List;

/**
 * 元数据总线整体状态（metadata-sync-bus.md §9.2）。
 *
 * @param committedHead MySQL 权威提交水位 —— <b>唯一</b>的落后判定基准
 * @param redisHead     Redis 水位镜像，仅作诊断字段展示；它比 MySQL 大说明有异常提示源，
 *                      比 MySQL 小只是延迟。绝不能拿它当目标真相
 * @param instances     当前存活实例（ZSET 惰性清理过期成员后取回）
 */
public record MetadataSyncStatusVO(
    long committedHead,
    String redisHead,
    List<MetadataInstanceVO> instances) {
}

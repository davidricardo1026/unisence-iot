package com.unisence.iot.common.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;

/**
 * 一次业务提交所影响的<b>一个聚合根</b>（metadata-sync-bus.md §四）。
 *
 * <p>这是把「全量刷新」收敛成「聚合根增量刷新」的最小单位：admin 的 ApplicationService
 * 在事务内显式收集本次变化范围，engine 按 {@code (metaKey, scopeId)} 去重后只重查这些根。
 *
 * <p>{@code scopeId} 一律使用 MySQL 内部代理 ID，不用可变展示名，也不用可能跨产品类型
 * 重复的裸 {@code product_key}。
 *
 * @param metaKey 元数据域
 * @param scopeId 聚合根 ID；{@code 0} 表示该域全量强制重建
 */
public record MetadataScope(MetaKeyEnum metaKey, long scopeId) {

    /**
     * 全域重建的哨兵 scopeId。
     */
    public static final long FULL_REBUILD = 0L;

    public MetadataScope {
        if (metaKey == null || scopeId < 0) {
            throw new IllegalArgumentException("metaKey 不能为空且 scopeId 不能为负");
        }
    }

    public static MetadataScope of(MetaKeyEnum metaKey, long scopeId) {
        return new MetadataScope(metaKey, scopeId);
    }

    /**
     * 该域全量强制重建。
     */
    public static MetadataScope fullRebuild(MetaKeyEnum metaKey) {
        return new MetadataScope(metaKey, FULL_REBUILD);
    }

    public boolean isFullRebuild() {
        return scopeId == FULL_REBUILD;
    }
}

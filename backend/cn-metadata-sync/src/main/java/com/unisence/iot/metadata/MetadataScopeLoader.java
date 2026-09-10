package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;

import java.util.Set;

/**
 * 单个元数据域的原始数据加载器（metadata-sync-bus.md §13.3）。
 *
 * <p>实现类固定为四个：{@code ProductMetadataLoader}、{@code DeviceMetadataLoader}、
 * {@code ThingModelMetadataLoader}、{@code RuleMetadataLoader}。
 *
 * <p><b>loader 禁止各自持有 {@code volatile} 缓存引用</b>。若每个 loader 都缓存自己那一份，
 * 「一次原子替换整个根」就名存实亡：四个引用分别替换意味着存在四种半新半旧的组合。
 */
public interface MetadataScopeLoader {

    MetaKeyEnum metaKey();

    /**
     * 在 {@link MetadataReadView} 的<b>同一个</b>只读一致性快照中批量加载原始数据。
     *
     * @param scopeIds 去重后的聚合根 ID；含 {@code 0} 表示全域
     * @throws RuntimeException 加载失败；调用方据此放弃整个候选根并保留 LKG
     */
    MetadataRawPatch load(Set<Long> scopeIds, MetadataReadView readView);
}

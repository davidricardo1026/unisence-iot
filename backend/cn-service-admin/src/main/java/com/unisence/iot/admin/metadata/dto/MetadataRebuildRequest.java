package com.unisence.iot.admin.metadata.dto;

import com.unisence.iot.common.constant.MetaKeyEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

/**
 * 强制重建请求（metadata-sync-bus.md §9.3）。
 *
 * <p>{@code scopeIds} 留空即该域全量重建（落库为 {@code scope_id=0}）。
 * 全域重建的代价是所有实例都要重跑该域构建，因此是显式选择，不做默认。
 */
@Data
public class MetadataRebuildRequest {

    @NotNull(message = "元数据域不能为空")
    private MetaKeyEnum metaKey;

    /**
     * 指定聚合根 ID；为空表示全域重建。
     */
    private Set<Long> scopeIds;
}

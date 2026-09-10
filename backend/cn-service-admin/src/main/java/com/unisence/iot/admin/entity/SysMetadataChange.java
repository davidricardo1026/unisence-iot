package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_sys_metadata_change} —— C 类只增流水，只记录失效范围，不复制业务值。
 *
 * <p>继承 {@link BaseAuditEntity} 而非 {@code BaseEntity}：变更目录只增不改，
 * 没有就地修改语义，因此没有 update_* / deleted / version。清理靠按 {@code commit_seq}
 * 前缀的物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_metadata_change")
public class SysMetadataChange extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long changeId;

    /**
     * 所属业务写事务的提交序号；engine 的读取游标。
     */
    private Long commitSeq;

    /**
     * {@code MetaKeyEnum} 的名字。
     */
    private String metaKey;

    /**
     * 受影响聚合根 ID；{@code 0} 表示该域全量强制重建。
     */
    private Long scopeId;
}

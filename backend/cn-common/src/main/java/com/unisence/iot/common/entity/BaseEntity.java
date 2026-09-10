package com.unisence.iot.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 平台全功能业务实体基类（唯一真理源见 harness logic-delete.md §3.1）。
 * 在 BaseAuditEntity（createBy/createTime）之上追加：更新审计 + 逻辑删除 + 乐观锁。
 * 适用于：A 业务实体表——会被就地修改，故有 update_*；软删（deleted=ID）+ 乐观锁（version）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class BaseEntity extends BaseAuditEntity {

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除字段
     *
     * value  = "0" → 未删除固定为 0，MP 自动在所有查询追加 WHERE deleted = 0
     * delval = "0" → 安全占位符。防止误调用 Mapper 层 deleteById 时产生错误数据。
     *               实际删除值（主键ID）由 BaseServiceImpl.removeById() 统一注入。
     */
    @TableLogic(value = "0", delval = "0")
    private Long deleted;

    @Version
    private Integer version;
}

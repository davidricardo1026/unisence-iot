package com.unisence.iot.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仅创建审计的不可变记录基类（唯一真理源见 harness logic-delete.md §3.1）。
 * 只含 createBy / createTime，无 update_* / deleted / version。
 * <p>适用于：<b>B 关联中间表</b>（建立/解绑，物理删除）、<b>C 日志/流水表</b>（只增不改）。
 * 这两类都无"就地修改"语义，故不含 update_*；BaseServiceImpl.removeById 检测非 BaseEntity → 走物理删除。
 */
@Data
public abstract class BaseAuditEntity {

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

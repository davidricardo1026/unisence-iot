package com.unisence.iot.admin.rule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启停请求。启用前会重新校验与编译，故与普通字段更新分开。
 */
@Data
public class RuleStatusRequest {

    @NotNull(message = "状态不能为空")
    private Integer status;

    @NotNull(message = "乐观锁版本不能为空")
    private Integer version;
}

package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductCloneRequest {

    @Size(max = 100, message = "产品名称不能超过100字符")
    private String productName;

    /**
     * 默认继承模板编码；random 时由服务端生成不与现有产品冲突的新编码。
     */
    @Pattern(regexp = "inherit|random", message = "产品编码策略仅支持 inherit 或 random")
    private String keyStrategy = "inherit";
}

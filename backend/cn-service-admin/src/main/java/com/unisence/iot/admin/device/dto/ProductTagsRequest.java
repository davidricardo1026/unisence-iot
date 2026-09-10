package com.unisence.iot.admin.device.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ProductTagsRequest {

    @NotNull(message = "tagIds 不能为空")
    private List<Long> tagIds;
}

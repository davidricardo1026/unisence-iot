package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DictDataVO {
    private Long dictDataId;
    private Integer sortOrder;
    private String dictLabel;
    private String dictValue;
    private String dictType;
    private Integer status;
    private Integer version;
    private LocalDateTime createTime;
}

package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DictTypeVO {
    private Long dictTypeId;
    private String dictName;
    private String dictType;
    private Integer status;
    private Integer version;
    private LocalDateTime createTime;
}

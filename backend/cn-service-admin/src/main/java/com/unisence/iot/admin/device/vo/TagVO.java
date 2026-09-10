package com.unisence.iot.admin.device.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TagVO {
    private Long tagId;
    private String tagKey;
    private String tagValue;
    private String color;
    private String description;
    private Integer version;
    private LocalDateTime createTime;
}

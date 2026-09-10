package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class MenuTreeVO {
    private Long menuId;
    private Long parentId;
    private String menuName;
    private String path;
    private String component;
    private String perms;
    private String icon;
    private Integer sortOrder;
    private Integer isVisible;
    private String menuType;
    private Long moduleId;
    private Integer version;
    private LocalDateTime createTime;
    private List<MenuTreeVO> children = new ArrayList<>();
}

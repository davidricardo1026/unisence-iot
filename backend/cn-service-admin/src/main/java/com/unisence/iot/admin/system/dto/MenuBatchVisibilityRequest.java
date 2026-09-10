package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MenuBatchVisibilityRequest {
    /**
     * 当前所有可见的菜单 ID 列表（包含半选的父节点）
     */
    @NotEmpty(message = "可见列表不能为空")
    private List<Long> visibleIds;

    /**
     * 为了保证乐观锁，需要传入 ID 与版本的映射
     */
    private List<MenuVersionItem> items;

    @Data
    public static class MenuVersionItem {
        private Long menuId;
        private Integer version;
    }
}

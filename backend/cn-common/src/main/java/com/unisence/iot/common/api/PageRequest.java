package com.unisence.iot.common.api;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用泛型分页请求包装器
 *
 * @param <T> 业务查询对象类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageRequest<T> {

    /**
     * 全局统一分页默认值
     */
    public static final int DEFAULT_PAGE_NUM = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 当前页码
     */
    private Integer pageNum;

    /**
     * 每页条数
     */
    private Integer pageSize;

    /**
     * 具体的业务查询条件对象
     */
    @Valid
    private T query;

    /**
     * 当前页码（未传时返回全局默认值 1），调用方无需再判空
     */
    public int getPageNum() {
        return pageNum != null ? pageNum : DEFAULT_PAGE_NUM;
    }

    /**
     * 每页条数（未传时返回全局默认值 20），调用方无需再判空
     */
    public int getPageSize() {
        return pageSize != null ? pageSize : DEFAULT_PAGE_SIZE;
    }

    /**
     * 快捷构造分页请求
     */
    public static <T> PageRequest<T> of(Integer pageNum, Integer pageSize, T query) {
        return new PageRequest<>(pageNum, pageSize, query);
    }

}

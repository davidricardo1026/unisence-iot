export interface ApiResponse<T> {
    code: number
    message: string
    data: T
}

export interface PageResult<T> {
    list: T[]
    total: number
}

/**
 * 通用分页请求包装器
 * 与后端 com.unisence.iot.common.api.PageRequest 对齐
 */
export interface PageRequest<T> {
    pageNum?: number
    pageSize?: number
    query?: T
}

/**
 * 分页默认配置
 */
export const PAGE_DEFAULT = {
    PAGE_NUM: 1,
    PAGE_SIZE: 20,
    PAGE_SIZES: [10, 20, 50, 100, 500, 1000]
} as const

import axios, {type AxiosInstance, type AxiosRequestConfig, type AxiosResponse} from 'axios'
import {ElMessage} from 'element-plus'
import type {ApiResponse} from '@/types/api'

/**
 * 递归展平对象为点号表示法 (用于 GET 请求参数对齐 Spring MVC)
 * 如 { query: { name: 'admin' } } -> { 'query.name': 'admin' }
 */
type QueryPrimitive = string | number | boolean | Date
type QueryValue = QueryPrimitive | QueryValue[] | QueryObject | null | undefined

interface QueryObject {
    [key: string]: QueryValue
}

function flattenParams(obj: QueryObject, prefix = ''): Record<string, QueryPrimitive | QueryPrimitive[]> {
    const result: Record<string, QueryPrimitive | QueryPrimitive[]> = {}
    for (const [key, value] of Object.entries(obj)) {
        if (value === undefined || value === null || value === '') continue

        const fullKey = prefix ? `${prefix}.${key}` : key
        if (typeof value === 'object' && !Array.isArray(value) && !(value instanceof Date)) {
            Object.assign(result, flattenParams(value, fullKey))
        } else {
            result[fullKey] = value as QueryPrimitive | QueryPrimitive[]
        }
    }
    return result
}

const service: AxiosInstance = axios.create({
    baseURL: '/api',
    timeout: 30000,
    headers: {
        'Content-Type': 'application/json;charset=UTF-8',
    },
    // 自定义参数序列化：处理嵌套对象为点号表示法
    paramsSerializer: {
        serialize: (params) => {
            const flattened = flattenParams(params as QueryObject)
            const searchParams = new URLSearchParams()
            for (const key in flattened) {
                const val = flattened[key]
                if (Array.isArray(val)) {
                    val.forEach(v => searchParams.append(key, String(v)))
                } else {
                    searchParams.append(key, String(val))
                }
            }
            return searchParams.toString()
        }
    }
})

/** 防重复跳转登录页 */
let isRedirecting = false

function redirectToLogin() {
    if (isRedirecting) return
    isRedirecting = true
    localStorage.removeItem('unisence_token')
    localStorage.removeItem('unisence_user_info')
    ElMessage.warning('登录已过期，即将跳转到登录页')
    setTimeout(() => {
        const currentPath = window.location.pathname
        const loginPath = currentPath !== '/login' ? `/login?redirect=${encodeURIComponent(currentPath)}` : '/login'
        window.location.replace(loginPath)
    }, 1500)
}

service.interceptors.request.use(
    (config) => {
        const token = localStorage.getItem('unisence_token')
        if (token) {
            config.headers.Authorization = `Bearer ${token}`
        }
        return config
    },
    (error) => Promise.reject(error),
)

service.interceptors.response.use(
    (response: AxiosResponse<ApiResponse<unknown>>) => {
        const res = response.data
        // 非 2xx 业务码按业务错误处理
        if (res.code !== undefined && res.code !== 0 && res.code !== 200) {
            // 👑 统一由组件层的 catch 块进行 ElMessage 弹出，此处只负责 reject
            return Promise.reject(new Error(res.message || '请求失败'))
        }
        return response
    },
    (error) => {
        if (error.response) {
            const status = error.response.status
            if (status === 401) {
                redirectToLogin()
                return Promise.reject(new Error('登录已过期'))
            }
            if (status === 403) {
                ElMessage.error('无权限访问')
            } else if (status >= 500) {
                ElMessage.error('服务器异常，请稍后重试')
            }
            // 4xx 等业务异常 (如 400 参数错误, 409 唯一性冲突) 
            // 统一由组件层 catch(err) 中的 ElMessage.error(err.message) 弹出，此处不再重复处理
        } else {
            ElMessage.error('网络异常，请检查网络连接')
        }
        // 确保把后端的错误信息带出去，以便组件层能够展示具体的业务错误（如：该字典下标签[xx]已存在）
        const errorMessage = error.response?.data?.message || error.message || '请求失败'
        return Promise.reject(new Error(errorMessage))
    },
)

export function request<T>(config: AxiosRequestConfig): Promise<ApiResponse<T>> {
    return service.request<ApiResponse<T>>(config).then((res) => res.data)
}

export default service

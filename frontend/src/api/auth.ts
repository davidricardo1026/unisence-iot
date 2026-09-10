import {request} from '@/utils/request'

export interface LoginParams {
    userCode: string
    password: string // Front-end SHA-256 hashed 64-character hexadecimal string
    identityType: string // "local", etc.
}

export interface LoginResult {
    token: string
}

/**
 * 获取启用的登录方式列表
 */
export function getEnabledAuthTypes() {
    return request<string[]>({
        url: '/auth/enabled-types',
        method: 'get',
    })
}

/**
 * 登出（让后端清除 Redis 会话）
 */
export function logout() {
    return request<void>({
        url: '/auth/logout',
        method: 'post',
    })
}

/**
 * 登录
 */
export function login(data: LoginParams) {
    return request<LoginResult>({
        url: '/auth/login',
        method: 'post',
        data: {
            identityType: data.identityType,
            authParams: {
                userCode: data.userCode,
                password: data.password,
            },
        },
    })
}

import type {Directive} from 'vue'
import {useUserStore} from '@/store/modules/user'

/**
 * 按钮权限校验指令
 * 使用示例：<el-button v-hasPermi="['sys:user:add']">新增</el-button>
 */
export const hasPermi: Directive = {
    mounted(el, binding) {
        const {value} = binding
        const userStore = useUserStore()
        const userCode = userStore.userInfo.userCode
        const all_permission = '*'
        const permissions = userStore.userInfo.perms || []

        if (value && value instanceof Array && value.length > 0) {
            const permissionFlag = value

            // 1. superAdmin 特权旁路：直接放行
            if (userCode === 'superAdmin') {
                return
            }

            // 2. 正常权限匹配 (支持 * 全局匹配，虽然后端主要给 superAdmin 发 *)
            const hasPermissions = permissions.some(permission => {
                return all_permission === permission || permissionFlag.includes(permission)
            })

            // 3. 匹配失败，从 DOM 中移除元素
            if (!hasPermissions) {
                el.parentNode?.removeChild(el)
            }
        } else {
            throw new Error(`请设置操作权限标签值，如 v-hasPermi="['sys:user:add']"`)
        }
    }
}

/**
 * 脚本内权限校验函数
 * @param value 权限编码数组
 * @returns 是否有权限
 */
export function checkPermi(value: string[]): boolean {
    if (!value || value.length === 0) return false

    const userStore = useUserStore()
    const userCode = userStore.userInfo.userCode

    // 1. superAdmin 特权旁路
    if (userCode === 'superAdmin') return true

    // 2. 权限集匹配
    const permissions = userStore.userInfo.perms || []
    return value.some(v => permissions.includes('*') || permissions.includes(v))
}

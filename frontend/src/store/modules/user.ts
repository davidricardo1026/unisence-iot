import {defineStore} from 'pinia'
import {ref} from 'vue'
import {convertToMenuItem, getUserProfile} from '@/api/system'
import {login as apiLogin, type LoginParams, logout as apiLogout} from '@/api/auth'
import {sha256} from '@/utils/crypto'
import {usePermissionStore} from "@/store/modules/permission";
import type {SystemMenu} from "@/types/menu";

export interface UserInfo {
    id: string
    userCode: string
    nickname: string
    deptId?: number
    deptName?: string
    roles?: string[]
    perms?: string[]
    avatar?: string
}

export const useUserStore = defineStore('user', () => {
    const token = ref<string>(localStorage.getItem('unisence_token') || '')
    const userInfo = ref<UserInfo>(
        JSON.parse(localStorage.getItem('unisence_user_info') || '{}') || {
            id: '1',
            userCode: 'admin',
            nickname: '管理员',
        }
    )

    function setToken(value: string) {
        token.value = value
        localStorage.setItem('unisence_token', value)
    }

    function setUserInfo(value: UserInfo) {
        userInfo.value = value
        localStorage.setItem('unisence_user_info', JSON.stringify(value))
    }

    /**
     * 同步服务器最新的用户信息及权限列表
     */
    async function syncProfile() {
        const permissionStore = usePermissionStore()
        try {
            const res = await getUserProfile()
            if (res && res.data) {
                const data = res.data
                setUserInfo({
                    id: String(data.userId),
                    userCode: data.userCode,
                    nickname: data.userName || data.userCode || '管理员',
                    deptId: data.deptId,
                    deptName: data.deptName,
                    roles: data.roles,
                    perms: data.perms,
                })

                // 统一同步菜单树到 permission store
                if (data.menus) {
                    const mappedMenus: SystemMenu[] = data.menus
                        .filter((root) => root.menuType !== 'F')
                        .map((root) => ({
                            id: String(root.menuId),
                            title: root.menuName,
                            path: root.path || undefined,
                            icon: root.icon || undefined,
                            children: (root.children || []).map(child => convertToMenuItem(child, root.path || '')),
                        }))
                    permissionStore.setWholeMenus(mappedMenus)

                    if (!permissionStore.syncSystemByPath(window.location.pathname)) {
                        const hasHomePerm = mappedMenus.some(menu => menu.id === '1')
                        if (window.location.pathname === '/dashboard' && hasHomePerm) {
                            permissionStore.changeSystem('1')
                        }
                    }
                    permissionStore.setLoaded(true)
                }

                return data
            }
        } catch (err) {
            console.error('同步用户信息失败', err)
        }
    }

    async function login(params: Omit<LoginParams, 'password'> & { password: string }) {
        // 密码在前端进行 SHA-256 离散
        const hashedPwd = await sha256(params.password)
        const response = await apiLogin({
            userCode: params.userCode,
            password: hashedPwd,
            identityType: params.identityType || 'local',
        })

        if (response && response.data) {
            const loginResult = response.data
            setToken(loginResult.token)

            // 👑 登录成功后，立即调用 syncProfile 获取完整的用户信息和权限列表
            const profile = await syncProfile()
            return {...loginResult, ...profile}
        }
        throw new Error('登录失败，返回数据为空')
    }

    async function logout() {
        try {
            await apiLogout()
        } catch (e) {
            console.warn('后端退出登录失败，继续清理本地状态', e)
        }
        token.value = ''
        userInfo.value = {
            id: '',
            userCode: '',
            nickname: '',
        }
        localStorage.removeItem('unisence_token')
        localStorage.removeItem('unisence_user_info')
    }

    return {token, userInfo, setToken, setUserInfo, syncProfile, login, logout}
})

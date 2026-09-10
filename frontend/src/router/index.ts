import {createRouter, createWebHistory} from 'vue-router'
import {constantRoutes} from './routes'
import {useUserStore} from '@/store/modules/user'
import {usePermissionStore} from '@/store/modules/permission'

const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes: constantRoutes,
    scrollBehavior: () => ({left: 0, top: 0}),
})

// 白名单路由
const whiteList = ['/login']

router.beforeEach(async (to) => {
    const userStore = useUserStore()
    const hasToken = userStore.token

    if (hasToken) {
        // 登录后同步用户信息及侧边栏菜单（统一通过 profile 接口）
        const permStore = usePermissionStore()
        if (!permStore.loaded) {
            await userStore.syncProfile()
        }
        if (to.path === '/login') {
            return {path: '/'}
        }

        // 👑 首页 vs 欢迎页智能分发：访问根路径或首页时，依据首页权限决定去向
        if (to.path === '/' || to.path === '/dashboard') {
            const hasHomePerm = permStore.wholeMenus.some((m) => m.id === '1')
            if (!hasHomePerm) {
                return {path: '/welcome'}
            }
            if (to.path === '/') {
                return {path: '/dashboard'}
            }
        }
    } else {
        if (!whiteList.includes(to.path)) {
            return `/login?redirect=${to.path}`
        }
    }
})

/**
 * 任意导航完成后都由路由反查所属模块：顶部菜单、历史 Tab、浏览器前进后退和直达 URL 保持一致。
 */
router.afterEach((to) => {
    const permissionStore = usePermissionStore()
    permissionStore.syncSystemByPath(to.path)
})

export default router

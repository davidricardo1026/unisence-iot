import {defineStore} from 'pinia'
import {computed, ref} from 'vue'
import type {MenuItem, SystemMenu} from '@/types/menu'

export const usePermissionStore = defineStore('permission', () => {
    const currentSystemId = ref<string>('')
    const wholeMenus = ref<SystemMenu[]>([])
    const loaded = ref(false)
    // 👑 记录每个模块上次访问的路径，切回模块时恢复现场
    const lastPathByModule = ref<Record<string, string>>({})

    function rememberPath(systemId: string, path: string) {
        if (systemId) {
            lastPathByModule.value[systemId] = path
        }
    }

    const sidebarMenus = computed<MenuItem[]>(() => {
        const sys = wholeMenus.value.find((item) => item.id === currentSystemId.value)
        return sys?.children ?? []
    })

    const currentSystem = computed(() =>
        wholeMenus.value.find((item) => item.id === currentSystemId.value),
    )

    function changeSystem(systemId: string) {
        currentSystemId.value = systemId
    }

    function normalizeModulePath(path: string): string {
        return path.startsWith('/system-device')
            ? '/device' + path.slice('/system-device'.length)
            : path
    }

    function matchesPath(path: string, menuPath?: string): boolean {
        if (!menuPath) return false
        const normalizedPath = normalizeModulePath(path)
        const normalizedMenuPath = normalizeModulePath(menuPath)
        return normalizedPath === normalizedMenuPath
            || normalizedPath.startsWith(normalizedMenuPath.endsWith('/') ? normalizedMenuPath : `${normalizedMenuPath}/`)
    }

    /**
     * 由当前路由反查所属模块；选择匹配路径最长的模块，避免 /system 误匹配 /system-setting。
     */
    function findSystemIdByPath(path: string): string | undefined {
        let matchedSystemId: string | undefined
        let matchedPathLength = -1

        const consider = (systemId: string, menuPath?: string) => {
            if (!menuPath || !matchesPath(path, menuPath)) return
            const pathLength = normalizeModulePath(menuPath).length
            if (pathLength > matchedPathLength) {
                matchedSystemId = systemId
                matchedPathLength = pathLength
            }
        }

        const walk = (systemId: string, items: MenuItem[]) => {
            for (const item of items) {
                consider(systemId, item.path)
                if (item.children?.length) walk(systemId, item.children)
            }
        }

        for (const system of wholeMenus.value) {
            consider(system.id, system.path)
            walk(system.id, system.children)
        }

        return matchedSystemId
    }

    /** 路由是当前模块的唯一事实来源；模块切换不清空完整菜单树或跨模块页签。 */
    function syncSystemByPath(path: string): string | undefined {
        const systemId = findSystemIdByPath(path)
        if (systemId && currentSystemId.value !== systemId) {
            currentSystemId.value = systemId
        }
        return systemId
    }

    function setWholeMenus(menus: SystemMenu[]) {
        wholeMenus.value = menus
    }

    /** 获取某系统下第一个可访问路由 */
    function getFirstRoutePath(systemId: string): string | undefined {
        const sys = wholeMenus.value.find((item) => item.id === systemId)
        if (!sys) return undefined

        // 👑 优先返回子菜单中的第一个有效路径
        const walk = (items: MenuItem[]): string | undefined => {
            for (const item of items) {
                if (item.path) return item.path
                if (item.children?.length) {
                    const found = walk(item.children)
                    if (found) return found
                }
            }
            return undefined
        }

        const firstChildPath = walk(sys.children || [])
        // 👑 如果没有子菜单，但模块本身有路径，则返回模块自己的路径
        return firstChildPath || sys.path
    }

    /** 设置加载完成标志 */
    function setLoaded(val: boolean) {
        loaded.value = val
    }

    return {
        currentSystemId,
        wholeMenus,
        sidebarMenus,
        currentSystem,
        loaded,
        lastPathByModule,
        rememberPath,
        changeSystem,
        findSystemIdByPath,
        syncSystemByPath,
        setWholeMenus,
        setLoaded,
        getFirstRoutePath,
    }
})

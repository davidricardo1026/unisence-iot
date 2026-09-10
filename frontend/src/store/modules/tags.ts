import {defineStore} from 'pinia'
import {ref} from 'vue'
import type {RouteLocationNormalized} from 'vue-router'

export interface TagView {
    path: string
    title: string
    name?: string
}

/** 落地页（大屏/欢迎页等）不进入页签体系 */
const EXCLUDED_PATHS = ['/', '/dashboard', '/welcome', '/login']

export const useTagsStore = defineStore('tags', () => {
    const visitedViews = ref<TagView[]>([])
    const cachedViews = ref<string[]>([])

    function addView(route: RouteLocationNormalized) {
        if (!route.meta?.title || route.meta?.hideInTabs || EXCLUDED_PATHS.includes(route.path)) return
        const exists = visitedViews.value.some((v) => v.path === route.path)
        if (!exists) {
            visitedViews.value.push({
                path: route.path,
                title: String(route.meta.title),
                name: route.name as string | undefined,
            })
        }
        const name = route.name as string | undefined
        if (name && route.meta?.keepAlive && !cachedViews.value.includes(name)) {
            cachedViews.value.push(name)
        }
    }

    function removeView(path: string) {
        const index = visitedViews.value.findIndex((v) => v.path === path)
        if (index > -1) {
            const removed = visitedViews.value[index]
            visitedViews.value.splice(index, 1)
            if (removed?.name) {
                cachedViews.value = cachedViews.value.filter((n) => n !== removed.name)
            }
        }
    }

    function clearAll() {
        visitedViews.value = []
        cachedViews.value = []
    }

    return {visitedViews, cachedViews, addView, removeView, clearAll}
})

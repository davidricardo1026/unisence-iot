<script setup lang="ts">
import type {Component, VNode} from 'vue'
import AppHeader from './components/AppHeader.vue'
import AppSidebar from './components/AppSidebar.vue'
import AppTabs from './components/AppTabs.vue'
import {useTagsStore} from '@/store/modules/tags'
import {usePermissionStore} from '@/store/modules/permission'
import type {MenuItem} from '@/types/menu'

/**
 * 👑 keep-alive 命名包装器
 * include 按组件名匹配，但视图 SFC 均为 index.vue（无显式 name），永远无法命中。
 * 这里把路由组件动态包一层以“路由名”命名的壳，使缓存真正生效。
 */
const wrapperMap = new Map<string, Component>()

function wrapComponent(component: VNode | undefined, name: unknown): Component | VNode | undefined {
  if (!component || typeof name !== 'string' || !name) return component
  let wrapper = wrapperMap.get(name)
  if (!wrapper) {
    wrapper = {
      name,
      render: () => h(component.type as Component, component.props, component.children),
    }
    wrapperMap.set(name, wrapper)
  }
  return wrapper
}

const route = useRoute()
const tagsStore = useTagsStore()
const permissionStore = usePermissionStore()
const collapsed = ref(false)
const isMobile = ref(false)

/** 布局模式：welcome / dashboard 为无侧栏落地页，其余为标准后台 */
const mode = computed<'landing' | 'system'>(() =>
    route.path === '/welcome' || route.path === '/dashboard' ? 'landing' : 'system',
)

function checkWidth() {
  const width = window.innerWidth
  if (width < 768) {
    isMobile.value = true
    collapsed.value = true
  } else if (width < 1024) {
    isMobile.value = false
    collapsed.value = true
  } else {
    isMobile.value = false
    collapsed.value = false
  }
}

const breadcrumbs = computed(() => {
  const result: string[] = []
  const sys = permissionStore.currentSystem
  if (sys) result.push(sys.title)

  // 👑 递归查找当前路径在菜单树中的完整标题链（支持任意深度）
  const findTrail = (items: MenuItem[], trail: string[]): string[] | undefined => {
    for (const item of items) {
      const next = [...trail, item.title]
      if (item.path === route.path) return next
      if (item.children?.length) {
        const found = findTrail(item.children, next)
        if (found) return found
      }
    }
    return undefined
  }

  const trail = sys?.children ? findTrail(sys.children, []) : undefined
  if (trail) {
    result.push(...trail)
  } else if (route.meta?.title) {
    result.push(route.meta.title as string)
  }
  return result
})

watch(
    () => route.fullPath,
    () => {
      // 👑 落地页不进页签（tags store 内部还有一道过滤），并记录当前模块的访问现场
      if (mode.value === 'system') {
        tagsStore.addView(route)
        permissionStore.rememberPath(permissionStore.currentSystemId, route.path)
      }
      if (isMobile.value) {
        collapsed.value = true
      }
    },
    {immediate: true},
)

onMounted(() => {
  checkWidth()
  window.addEventListener('resize', checkWidth)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', checkWidth)
})
</script>

<template>
  <div class="flex h-full flex-col overflow-hidden bg-gray-50">
    <AppHeader/>

    <div class="flex min-h-0 flex-1 relative">
      <!-- 系统模式专属：侧边栏 -->
      <AppSidebar
          v-if="mode === 'system'"
          :collapsed="collapsed"
          :class="[
          isMobile && !collapsed ? 'fixed left-0 top-9 bottom-0 z-50 shadow-2xl !w-[132px]' : '',
          isMobile && collapsed ? '!w-0 overflow-hidden border-none' : ''
        ]"
      />

      <!-- 移动端遮罩 -->
      <div
          v-if="mode === 'system' && isMobile && !collapsed"
          class="fixed inset-0 bg-black/30 z-40 transition-opacity duration-200"
          @click="collapsed = true"
      />

      <div class="flex min-w-0 flex-1 flex-col">
        <!-- 系统模式专属：面包屑栏 -->
        <div
            v-if="mode === 'system'"
            class="flex h-[34px] shrink-0 items-center gap-2 border-b border-gray-200 bg-white px-2"
        >
          <el-button
              text
              size="small"
              class="!p-0 h-6 w-6 inline-flex items-center justify-center"
              @click="collapsed = !collapsed"
          >
            <el-icon :size="14">
              <Fold v-if="!collapsed"/>
              <Expand v-else/>
            </el-icon>
          </el-button>
          <el-breadcrumb
              separator="/"
              class="flex-1"
          >
            <el-breadcrumb-item
                v-for="(item, index) in breadcrumbs"
                :key="index"
                class="!text-[11px]"
            >
              {{ item }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <!-- 系统模式专属：多页签栏 -->
        <AppTabs v-if="mode === 'system' && tagsStore.visitedViews.length > 0"/>

        <!-- 👑 全局唯一 router-view + keep-alive：跨布局切换保留页面状态 -->
        <main
            class="min-h-0 flex-1 overflow-hidden p-0 flex flex-col"
            :class="mode === 'system' ? 'border-t border-gray-200' : 'bg-gray-50/30'"
        >
          <router-view
              v-if="mode === 'landing' || tagsStore.visitedViews.length > 0"
              v-slot="{ Component: RouteComponent }"
          >
            <keep-alive :include="tagsStore.cachedViews">
              <component
                  :is="wrapComponent(RouteComponent, route.name)"
                  :key="route.fullPath"
                  class="h-full flex flex-col min-h-0"
              />
            </keep-alive>
          </router-view>
          <div
              v-else
              class="flex flex-1 items-center justify-center"
          >
            <el-empty
                description="暂无打开的页面，请从左侧菜单选择"
                :image-size="120"
            />
          </div>
        </main>
      </div>
    </div>
  </div>
</template>

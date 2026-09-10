<script setup lang="ts">
import {usePermissionStore} from '@/store/modules/permission'
import {useTagsStore} from '@/store/modules/tags'
import type {MenuItem} from '@/types/menu'

defineProps<{
  collapsed: boolean
}>()

const route = useRoute()
const router = useRouter()
const permissionStore = usePermissionStore()
const tagsStore = useTagsStore()

const activeMenu = computed(() => route.path)

function resolveIndex(item: MenuItem): string {
  return item.path || item.id
}

function handleSelect(index: string) {
  let path = index
  if (path.startsWith('/system-device')) {
    path = '/device' + path.slice('/system-device'.length)
  }
  if (path.startsWith('/')) {
    if (path === route.path) {
      // 👑 点击的是当前路由（路由不会变化）：手动恢复页签，复活内容区
      tagsStore.addView(route)
    } else {
      router.push(path)
    }
  }
}

/** 图标的默认兜底值 */
const DEFAULT_ICON = 'Menu'

function getIcon(icon?: string): string {
  return icon || DEFAULT_ICON
}
</script>

<template>
  <aside
      class="flex shrink-0 flex-col border-r border-gray-100 bg-white transition-all duration-200"
      :class="collapsed ? 'w-12' : 'w-[132px]'"
      style="box-shadow: none !important;"
  >
    <el-scrollbar class="flex-1">
      <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          :collapse-transition="false"
          class="compact-sidebar-menu"
          @select="handleSelect"
      >
        <template
            v-for="menu in permissionStore.sidebarMenus"
            :key="menu.id"
        >
          <el-sub-menu
              v-if="menu.children?.length"
              :index="menu.id"
          >
            <template #title>
              <el-icon>
                <component :is="getIcon(menu.icon)"/>
              </el-icon>
              <span>{{ menu.title }}</span>
            </template>
            <el-menu-item
                v-for="child in menu.children"
                :key="child.id"
                :index="resolveIndex(child)"
            >
              <el-icon>
                <component :is="getIcon(child.icon)"/>
              </el-icon>
              <span>{{ child.title }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item
              v-else
              :index="resolveIndex(menu)"
          >
            <el-icon>
              <component :is="getIcon(menu.icon)"/>
            </el-icon>
            <span>{{ menu.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-scrollbar>
  </aside>
</template>

<style scoped>
.compact-sidebar-menu {
  border-right: none !important;
  box-shadow: none !important;
}

/* 1. 非折叠状态下的菜单项样式与紧凑外边距 */
.compact-sidebar-menu:not(.el-menu--collapse) :deep(.el-menu-item),
.compact-sidebar-menu:not(.el-menu--collapse) :deep(.el-sub-menu__title) {
  height: 34px !important;
  line-height: 34px !important;
  font-size: 11px !important;
  border-radius: 4px;
  margin: 1px 4px;
  padding: 0 8px !important; /* 精简非折叠状态下的标题左右内边距，释放横向空间 */
  display: flex !important;
  align-items: center !important; /* 强制图标与文字绝对垂直居中对齐 */
  position: relative !important;
}

/* 调整标题图标与文字的间距 */
.compact-sidebar-menu:not(.el-menu--collapse) :deep(.el-menu-item) .el-icon,
.compact-sidebar-menu:not(.el-menu--collapse) :deep(.el-sub-menu__title) .el-icon {
  margin-right: 6px !important;
  font-size: 14px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
}

/* 确保文字节点完美对其 */
.compact-sidebar-menu:not(.el-menu--collapse) :deep(.el-menu-item) span,
.compact-sidebar-menu:not(.el-menu--collapse) :deep(.el-sub-menu__title) span {
  display: inline-flex !important;
  align-items: center !important;
  line-height: 1 !important; /* 消除默认行高干扰，配合 flex 垂直居中 */
}

/* 缩减一级菜单右侧箭头的多余间距与大小，并完美居中 */
.compact-sidebar-menu:not(.el-menu--collapse) :deep(.el-sub-menu__icon-arrow) {
  position: absolute !important;
  right: 8px !important;
  top: 50% !important;
  margin-top: 0 !important;
  transform: translateY(-50%) !important;
  font-size: 10px !important;
}

.compact-sidebar-menu :deep(.el-menu-item.is-active) {
  background-color: #eff6ff !important;
  color: #2563eb !important;
  font-weight: 500;
}

.compact-sidebar-menu :deep(.el-sub-menu .el-menu-item) {
  height: 30px !important;
  line-height: 30px !important;
  padding-left: 22px !important; /* 深度精简子级缩进，给予子项文本（如“部门管理”）充足空间 */
}

/* 2. 折叠状态下的超级紧凑与居中对齐 */
.compact-sidebar-menu.el-menu--collapse {
  width: 48px !important; /* 强制折叠后的 Element 菜单宽度为 48px 与 w-12 完美对齐 */
}

.compact-sidebar-menu.el-menu--collapse :deep(.el-menu-item),
.compact-sidebar-menu.el-menu--collapse :deep(.el-sub-menu__title) {
  height: 38px !important;
  line-height: 38px !important;
  padding: 0 !important;
  margin: 2px 4px !important; /* 缩小侧边间距，支持圆角背景 */
  border-radius: 6px;
  display: flex !important;
  justify-content: center !important;
  align-items: center !important;
}

/* 解决 Element Plus 折叠后右侧小箭头和文本溢出及居中问题 */
.compact-sidebar-menu.el-menu--collapse :deep(.el-sub-menu__icon-arrow) {
  display: none !important;
}

.compact-sidebar-menu.el-menu--collapse :deep(.el-menu-item) .el-icon,
.compact-sidebar-menu.el-menu--collapse :deep(.el-sub-menu__title) .el-icon {
  margin: 0 !important;
  font-size: 16px !important;
}
</style>

<script setup lang="ts">
import {ElMessage, ElMessageBox} from 'element-plus'
import {usePermissionStore} from '@/store/modules/permission'
import {useUserStore} from '@/store/modules/user'
import {useTagsStore} from '@/store/modules/tags'

const router = useRouter()
const route = useRoute()
const permissionStore = usePermissionStore()
const userStore = useUserStore()
const tagsStore = useTagsStore()
const appTitle = import.meta.env.VITE_APP_TITLE || '统一感知'

/** 仅「系统管理」进右区：path 为 /system 或 /system/...；禁止用 startsWith('/system') 误伤 /system-device 等 */
function isSystemAdminPath(path?: string): boolean {
  return path === '/system' || !!path?.startsWith('/system/')
}

const mainMenus = computed(() => permissionStore.wholeMenus.filter(m => !isSystemAdminPath(m.path)))
const adminMenus = computed(() => permissionStore.wholeMenus.filter(m => isSystemAdminPath(m.path)))

function handleHomeClick() {
  const hasHomePerm = permissionStore.wholeMenus.some(m => m.id === '1')
  if (hasHomePerm) {
    handleSystemChange('1')
  } else {
    // 没首页权限，去欢迎页（页签保留，便于切回系统模块时恢复）
    router.push('/welcome')
  }
}

function handleSystemChange(systemId: string) {
  const isOnLanding = route.path === '/welcome' || route.path === '/dashboard'
  let targetPath = permissionStore.lastPathByModule[systemId]
      || permissionStore.getFirstRoutePath(systemId)

  // 兼容旧菜单 path：/system-device → /device（未重新灌种子时）
  if (targetPath?.startsWith('/system-device')) {
    targetPath = '/device' + targetPath.slice('/system-device'.length)
  }

  // 同一模块且已在目标页才跳过；否则即使同模块也要跳转（避免“点了没反应”）
  if (
      permissionStore.currentSystemId === systemId
      && !isOnLanding
      && targetPath === route.path
  ) {
    return
  }

  if (targetPath) {
    router.push(targetPath)
  }
}

async function handleCommand(command: string) {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      })
      await userStore.logout()
      tagsStore.clearAll()
      ElMessage.success('已安全退出')
      router.push('/login')
    } catch (error) {
      if (isCancelError(error)) console.debug('用户取消退出登录')
      else console.error('退出登录失败', error)
    }
  }
}
</script>

<template>
  <header
      class="flex h-9 shrink-0 items-center justify-between border-b border-gray-200 bg-white px-4"
  >
    <div class="flex items-center gap-6">
      <div
          class="flex items-center gap-2 text-[13px] font-bold text-[#409eff] cursor-pointer select-none"
          @click="handleHomeClick"
      >
        <el-icon :size="14">
          <Monitor/>
        </el-icon>
        <span>{{ appTitle }}</span>
      </div>
      <nav class="flex gap-1">
        <el-button
            v-for="sys in mainMenus"
            :key="sys.id"
            :type="permissionStore.currentSystemId === sys.id ? 'primary' : 'default'"
            text
            size="small"
            class="!h-6 !text-[11px] font-medium"
            @click="handleSystemChange(sys.id)"
        >
          <el-icon
              v-if="sys.icon"
              class="mr-1"
          >
            <component :is="sys.icon"/>
          </el-icon>
          {{ sys.title }}
        </el-button>
      </nav>
    </div>

    <div class="flex items-center gap-2">
      <el-button
          v-for="sys in adminMenus"
          :key="sys.id"
          :type="permissionStore.currentSystemId === sys.id ? 'primary' : 'default'"
          text
          size="small"
          class="!h-6 !text-[11px] font-medium"
          @click="handleSystemChange(sys.id)"
      >
        <el-icon
            v-if="sys.icon"
            class="mr-1"
        >
          <component :is="sys.icon"/>
        </el-icon>
        {{ sys.title }}
      </el-button>
      <el-dropdown
          trigger="click"
          @command="handleCommand"
      >
        <div class="flex items-center gap-1.5 cursor-pointer outline-none">
          <el-avatar
              :size="20"
              class="bg-[#409eff]"
          >
            <el-icon :size="12">
              <User/>
            </el-icon>
          </el-avatar>
          <span class="text-[11px] text-gray-600 font-medium">{{ userStore.userInfo.nickname || '用户' }}</span>
          <el-icon
              :size="10"
              class="text-gray-400"
          >
            <ArrowDown/>
          </el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">
              <el-icon>
                <SwitchButton/>
              </el-icon>
              <span>退出登录</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

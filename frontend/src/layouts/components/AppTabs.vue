<script setup lang="ts">
import {useTagsStore} from '@/store/modules/tags'

const route = useRoute()
const router = useRouter()
const tagsStore = useTagsStore()

/** 下钻页不新开页签，回显其所属的最长父页签。 */
const activePath = computed(() => {
  const exact = tagsStore.visitedViews.find(tag => tag.path === route.path)
  if (exact) return exact.path
  return tagsStore.visitedViews
      .filter(tag => route.path.startsWith(`${tag.path}/`))
      .sort((left, right) => right.path.length - left.path.length)[0]?.path || route.path
})

function handleTabClick(path: string) {
  router.push(path)
}

function handleTabRemove(path: string) {
  tagsStore.removeView(path)
  if (route.path === path && tagsStore.visitedViews.length > 0) {
    const last = tagsStore.visitedViews[tagsStore.visitedViews.length - 1]
    router.push(last.path)
  }
  // 👑 最后一个页签被关闭：不跳转，停留在当前页面
}
</script>

<template>
  <div class="bg-white px-2 py-1">
    <el-tabs
        :model-value="activePath"
        type="card"
        class="layout-tabs compact-tabs"
        @tab-change="handleTabClick"
        @tab-remove="handleTabRemove"
    >
      <el-tab-pane
          v-for="tag in tagsStore.visitedViews"
          :key="tag.path"
          :label="tag.title"
          :name="tag.path"
          closable
      />
    </el-tabs>
  </div>
</template>

<style scoped>
.layout-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
  border-bottom: none !important;
  height: 24px !important;
}

.layout-tabs :deep(.el-tabs__nav-wrap) {
  height: 24px !important;
  line-height: 24px !important;
}

.layout-tabs :deep(.el-tabs__nav-scroll) {
  height: 24px !important;
}

.layout-tabs :deep(.el-tabs__nav) {
  height: 24px !important;
  border: none !important; /* 彻底去除 card 选项卡自带的外层粗边框 */
}

.compact-tabs :deep(.el-tabs__item) {
  height: 24px !important;
  line-height: 24px !important;
  font-size: 11px !important;
  padding: 0 8px !important;
  border: 1px solid #e4e7ed !important; /* 统一小巧边框 */
  border-radius: 4px 4px 0 0;
  margin-right: 4px;
  background-color: #f9fafb; /* 默认浅灰色背景 */
  transition: all 150ms ease;
}

.compact-tabs :deep(.el-tabs__item.is-active) {
  background-color: #ffffff !important; /* 激活时白色 */
  border-bottom-color: transparent !important; /* 激活时底边框透明 */
  color: #2563eb !important;
  font-weight: 600;
}

.compact-tabs :deep(.el-tabs__nav-next),
.compact-tabs :deep(.el-tabs__nav-prev) {
  line-height: 24px !important;
  height: 24px !important;
}
</style>

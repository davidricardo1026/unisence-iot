<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white p-3">
    <!-- 头部 -->
    <div class="compact-action-toolbar">
      <div>
        <h2 class="text-xs font-bold text-gray-700">
          缓存管理
        </h2>
        <p class="text-[11px] text-gray-400 mt-0.5">
          系统运行时缓存（Caffeine Local）监控与清除中心
        </p>
      </div>
      <el-button
          v-hasPermi="['sys:cache:list']"
          size="small"
          :loading="loading"
          @click="loadData"
      >
        <el-icon>
          <Refresh/>
        </el-icon>
        刷新
      </el-button>
    </div>

    <!-- 缓存列表 -->
    <div class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="list"
          border
          stripe
          size="small"
          height="100%"
      >
        <el-table-column
            prop="domain"
            label="缓存域"
            width="110"
        />
        <el-table-column
            prop="cacheName"
            label="缓存名"
            width="110"
        />
        <el-table-column
            prop="caffeineCacheName"
            label="Caffeine 实例"
            width="130"
        />
        <el-table-column
            prop="relatedBusiness"
            label="关联业务"
            min-width="140"
        />
        <el-table-column
            label="操作"
            width="80"
            align="center"
            fixed="right"
        >
          <template #default="{ row }">
            <el-popconfirm
                title="确认清除该域下所有缓存？"
                confirm-button-text="清除"
                @confirm="handleClear(row)"
            >
              <template #reference>
                <el-tooltip
                    content="清除全部"
                    placement="top"
                    :enterable="false"
                >
                  <el-button
                      v-hasPermi="['sys:cache:clear']"
                      size="small"
                      type="danger"
                      link
                  >
                    <el-icon>
                      <Delete/>
                    </el-icon>
                  </el-button>
                </el-tooltip>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import {ElMessage} from 'element-plus'
import {type CacheRegistryItemVO, clearCache, getCacheRegistry} from '@/api/system'

const loading = ref(false)
const list = ref<CacheRegistryItemVO[]>([])

async function loadData() {
  loading.value = true
  try {
    const res = await getCacheRegistry()
    list.value = res.data || []
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err, '加载缓存注册表失败'))
  } finally {
    loading.value = false
  }
}

async function handleClear(row: CacheRegistryItemVO) {
  try {
    await clearCache({domain: row.domain, scope: 'ALL'})
    ElMessage.success(`缓存域 [${row.cacheName}] 已清除`)
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err, '清除失败'))
  }
}

onMounted(() => {
  loadData()
})
</script>

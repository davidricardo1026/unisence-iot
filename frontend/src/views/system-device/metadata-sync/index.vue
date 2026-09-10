<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white p-3 gap-3">
    <div class="compact-action-toolbar">
      <div>
        <h2 class="text-xs font-bold text-gray-700">元数据同步</h2>
        <p class="text-[11px] text-gray-400 mt-0.5">查看权威水位与实例收敛状态，执行总线内重新提醒或强制重建</p>
      </div>
      <div class="flex gap-2">
        <el-tag v-if="polling" size="small" type="info" effect="plain">每 2 秒自动刷新中</el-tag>
        <el-button size="small" :loading="loading" @click="handleManualRefresh">刷新</el-button>
        <el-button
            v-hasPermi="['sys:metadata:rebuild']"
            size="small"
            :loading="operating"
            @click="handleRenotify"
        >
          重新提醒
        </el-button>
        <el-button
            v-hasPermi="['sys:metadata:rebuild']"
            type="primary"
            size="small"
            @click="dialogVisible = true"
        >
          强制重建
        </el-button>
      </div>
    </div>

    <div class="grid grid-cols-2 gap-3 max-w-[520px]">
      <div class="border border-gray-200 rounded px-3 py-2">
        <div class="text-[11px] text-gray-400">MySQL 权威水位</div>
        <div class="text-base font-semibold text-gray-700 mt-1">{{ status?.committedHead ?? '-' }}</div>
      </div>
      <div class="border border-gray-200 rounded px-3 py-2">
        <div class="text-[11px] text-gray-400">Redis 诊断水位</div>
        <div class="text-base font-semibold text-gray-700 mt-1">{{ status?.redisHead ?? '-' }}</div>
      </div>
    </div>

    <div class="compact-table-region">
      <el-table v-loading="loading" :data="status?.instances ?? []" border stripe size="small" height="100%">
        <el-table-column prop="instanceId" label="实例" min-width="220" show-overflow-tooltip/>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{row}">
            <el-tag size="small" :type="stateTagType(row.state)">{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="appliedHead" label="已应用水位" width="110" align="right"/>
        <el-table-column prop="desiredHead" label="目标水位" width="110" align="right"/>
        <el-table-column prop="lag" label="落后" width="80" align="right"/>
        <el-table-column prop="lastBuildDurationMs" label="构建耗时(ms)" width="115" align="right"/>
        <el-table-column prop="deviceCatalogEntries" label="设备目录数" width="110" align="right"/>
        <el-table-column label="最近成功" width="170">
          <template #default="{row}">{{ formatTime(row.lastSuccessAt) }}</template>
        </el-table-column>
        <el-table-column prop="consecutiveFailures" label="连续失败" width="90" align="right"/>
        <el-table-column prop="lastErrorCode" label="最近错误" min-width="120" show-overflow-tooltip/>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" title="强制重建元数据" width="480px" destroy-on-close>
      <el-form label-width="90px" size="small">
        <el-form-item required>
          <template #label><span><span class="text-red-500">*</span> 元数据域</span></template>
          <el-select v-model="rebuildForm.metaKey" class="w-[240px]">
            <el-option v-for="domain in domains" :key="domain.value" :label="domain.label" :value="domain.value"/>
          </el-select>
        </el-form-item>
        <el-form-item label="范围 ID">
          <el-input
              v-model="scopeIdsText"
              class="w-[320px]"
              placeholder="留空为该域全量重建，多个 ID 用逗号分隔"
          />
          <div class="text-[11px] text-gray-400 mt-1">全量重建成本较高，请优先填写受影响的聚合根 ID。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" size="small" :loading="operating" @click="handleRebuild">确认重建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  getMetadataSyncStatus,
  type MetadataDomain,
  type MetadataSyncStatusVO,
  rebuildMetadata,
  renotifyMetadata,
} from '@/api/system'
import {getErrorMessage} from '@/utils/error'

const domains: Array<{ label: string; value: MetadataDomain }> = [
  {label: '产品', value: 'IOT_PRODUCT'},
  {label: '设备', value: 'IOT_DEVICE'},
  {label: '物模型', value: 'IOT_THING_MODEL'},
  {label: '规则', value: 'IOT_RULES'},
]
const loading = ref(false)
const operating = ref(false)
const dialogVisible = ref(false)
const scopeIdsText = ref('')
const status = ref<MetadataSyncStatusVO>()
const rebuildForm = reactive<{ metaKey: MetadataDomain }>({metaKey: 'IOT_PRODUCT'})
const polling = ref(false)

const POLL_INTERVAL_MS = 2_000
const POLL_MAX_DURATION_MS = 15_000
let pollTimer: ReturnType<typeof setTimeout> | undefined
let pollDeadline = 0
let pollTargetHead: number | undefined
let pageActive = false
let pollGeneration = 0
let statusRequestSequence = 0

function stateTagType(state: string) {
  if (state === 'READY') return 'success'
  if (state === 'DEGRADED') return 'danger'
  if (state === 'CONVERGING') return 'warning'
  return 'info'
}

function formatTime(value?: number) {
  return value ? new Date(value).toLocaleString() : '-'
}

function parseScopeIds() {
  if (!scopeIdsText.value.trim()) return undefined
  const values = scopeIdsText.value.split(',').map(value => Number(value.trim()))
  if (values.some(value => !Number.isSafeInteger(value) || value <= 0)) {
    throw new Error('范围 ID 必须是用逗号分隔的正整数')
  }
  return [...new Set(values)]
}

async function loadStatus(silent = false) {
  const requestSequence = ++statusRequestSequence
  if (!silent) loading.value = true
  try {
    const response = await getMetadataSyncStatus()
    if (requestSequence !== statusRequestSequence) return false
    status.value = response.data
    return true
  } catch (error: unknown) {
    if (!silent) ElMessage.error(getErrorMessage(error, '加载元数据同步状态失败'))
    return false
  } finally {
    if (!silent) loading.value = false
  }
}

function instanceConverged(instance: MetadataSyncStatusVO['instances'][number], targetHead?: number) {
  if (instance.state !== 'READY' || Number(instance.lag || 0) > 0) return false
  return targetHead === undefined || Number(instance.appliedHead) >= targetHead
}

function needsPolling(targetHead?: number) {
  const instances = status.value?.instances || []
  if (targetHead !== undefined && instances.length === 0) return true
  return instances.some(instance => !instanceConverged(instance, targetHead))
}

function stopPolling(clearTarget = true) {
  pollGeneration++
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
  polling.value = false
  if (clearTarget) pollTargetHead = undefined
}

function scheduleNextPoll(generation = pollGeneration) {
  if (generation !== pollGeneration || !pageActive || document.hidden || !polling.value) return
  const remaining = pollDeadline - Date.now()
  if (remaining <= 0) {
    stopPolling()
    ElMessage.warning('状态同步仍在进行，可手动刷新')
    return
  }
  pollTimer = setTimeout(() => runStatusPoll(generation), Math.min(POLL_INTERVAL_MS, remaining))
}

async function runStatusPoll(generation: number) {
  pollTimer = undefined
  if (generation !== pollGeneration || !pageActive || document.hidden || !polling.value) return
  const loaded = await loadStatus(true)
  if (generation !== pollGeneration) return
  if (loaded && !needsPolling(pollTargetHead)) {
    stopPolling()
    return
  }
  scheduleNextPoll(generation)
}

function startPolling(targetHead?: number) {
  stopPolling(false)
  pollTargetHead = targetHead
  if (!needsPolling(targetHead)) {
    stopPolling()
    return
  }
  polling.value = true
  pollDeadline = Date.now() + POLL_MAX_DURATION_MS
  scheduleNextPoll()
}

async function refreshAndMaybePoll(targetHead?: number) {
  const loaded = await loadStatus()
  if (loaded) startPolling(targetHead)
}

function handleManualRefresh() {
  void refreshAndMaybePoll(pollTargetHead)
}

async function handleRenotify() {
  operating.value = true
  try {
    const {data} = await renotifyMetadata()
    ElMessage.success(`已重新发送水位 ${data} 的提醒`)
    await refreshAndMaybePoll(Number(data))
  } catch (error: unknown) {
    ElMessage.error(getErrorMessage(error, '重新提醒失败'))
  } finally {
    operating.value = false
  }
}

async function handleRebuild() {
  try {
    const scopeIds = parseScopeIds()
    await ElMessageBox.confirm(
        scopeIds?.length ? `确认重建 ${scopeIds.length} 个指定范围？` : '未填写范围 ID，将执行该域全量重建，确认继续？',
        '高成本操作确认',
        {type: 'warning', confirmButtonText: '确认重建'},
    )
    operating.value = true
    const {data} = await rebuildMetadata({metaKey: rebuildForm.metaKey, scopeIds})
    ElMessage.success(`已创建重建提交，水位 ${data}`)
    dialogVisible.value = false
    scopeIdsText.value = ''
    await refreshAndMaybePoll(Number(data))
  } catch (error: unknown) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getErrorMessage(error, '强制重建失败'))
  } finally {
    operating.value = false
  }
}

function handleVisibilityChange() {
  if (document.hidden) {
    stopPolling(false)
  } else if (pageActive) {
    void refreshAndMaybePoll(pollTargetHead)
  }
}

function activatePage() {
  if (pageActive) return
  pageActive = true
  void refreshAndMaybePoll(pollTargetHead)
}

function deactivatePage() {
  pageActive = false
  stopPolling(false)
}

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  activatePage()
})
onActivated(activatePage)
onDeactivated(deactivatePage)
onBeforeUnmount(() => {
  pageActive = false
  stopPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

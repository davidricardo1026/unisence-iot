<template>
  <div class="flex min-h-0 flex-1 flex-col bg-white p-2">
    <div class="mb-1 flex shrink-0 items-center gap-2 border-b border-gray-200 pb-1">
      <el-button text size="small" class="!p-1" aria-label="返回设备管理" @click="router.push({name: 'IotDevice'})">
        <el-icon>
          <ArrowLeft/>
        </el-icon>
      </el-button>
      <div class="min-w-0 flex-1 truncate text-[13px] font-semibold text-gray-800">
        {{ device?.deviceName || device?.deviceCode || '设备详情' }}
      </div>
      <span class="max-w-[180px] shrink-0 truncate font-mono text-[11px] text-gray-400">{{
          device?.deviceCode || '—'
        }}</span>
      <el-tag v-if="device" size="small" :type="statusType(device.status)">{{ statusLabel(device.status) }}</el-tag>
    </div>

    <el-tabs v-model="activeTab" class="device-detail-tabs flex min-h-0 flex-1 flex-col compact-tabs">
      <el-tab-pane label="概览" name="overview" class="min-h-0 overflow-auto">
        <el-descriptions v-if="device" :column="3" border size="small" class="mt-2">
          <el-descriptions-item label="设备名称">{{ device.deviceName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="设备编码"><span class="font-mono">{{ device.deviceCode }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <span class="inline-flex items-center gap-1">
              <el-icon :class="deviceStatusIconClass(device.status)">
                <CircleCheckFilled v-if="device.status === 1"/>
                <CircleCloseFilled v-else-if="device.status === 2"/>
                <WarningFilled v-else-if="device.status === 3"/>
                <Clock v-else/>
              </el-icon>
              <el-tag size="small" :type="statusType(device.status)">{{ statusLabel(device.status) }}</el-tag>
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="所属产品">{{
              product?.productName || device.productName || '—'
            }}
          </el-descriptions-item>
          <el-descriptions-item label="产品标识"><span
              class="font-mono">{{ product?.productKey || device.productKey || '—' }}</span></el-descriptions-item>
          <el-descriptions-item label="节点类型">{{ nodeTypeLabel(device.nodeType) }}</el-descriptions-item>
          <el-descriptions-item label="最近上线">{{ device.lastOnlineAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="经度">{{ device.longitude ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="纬度">{{ device.latitude ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="地址">{{ device.address || '—' }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <el-tab-pane label="设备模板" name="template" class="min-h-0 overflow-auto">
        <el-descriptions v-if="templateFields.length" :column="2" border size="small" class="mt-2">
          <el-descriptions-item v-for="field in templateFields" :key="field.key" :label="field.label">
            {{ device?.deviceFormData?.[field.key] ?? '—' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="该产品未定义设备模板" :image-size="72"/>
      </el-tab-pane>

      <el-tab-pane label="属性" name="properties" class="min-h-0 overflow-auto">
        <div class="mt-2 flex min-h-0 flex-col gap-2">
          <el-alert v-if="latestProperties && !latestProperties.available"
                    title="最新属性暂不可用，设备静态信息不受影响" type="warning" :closable="false"/>
          <div class="text-xs font-medium text-gray-600">当前值</div>
          <el-table :data="latestProperties?.properties || []" size="small" border
                    v-loading="latestPropertiesLoading" max-height="320">
            <el-table-column prop="identifier" label="标识符" min-width="140"/>
            <el-table-column label="最新值" min-width="180">
              <template #default="{ row }">{{ formatLatestValue(row.value) }}</template>
            </el-table-column>
            <el-table-column label="更新时间" min-width="170">
              <template #default="{ row }">{{ formatOccurredAt(row.occurredAt) }}</template>
            </el-table-column>
          </el-table>
          <el-collapse v-model="propertyPanels" class="property-history-collapse"
                       @change="handlePropertyCollapseChange">
            <el-collapse-item name="history">
              <template #title>
                <span class="text-xs font-medium text-gray-700">属性历史</span>
                <span class="ml-2 text-[11px] text-gray-400">展开后按需查询</span>
              </template>
              <div v-if="historyExpanded" class="flex flex-col gap-2 pb-1">
                <div class="flex flex-wrap items-center gap-2 rounded border border-gray-200 bg-gray-50 px-2 py-1.5">
                  <label class="text-xs text-gray-600">属性 <span class="text-red-500">*</span></label>
                  <el-select v-model="selectedIdentifier" size="small" class="!w-[220px]" placeholder="选择属性"
                             @change="handlePropertySelectionChange">
                    <el-option v-for="item in historyProperties" :key="item.identifier" :value="item.identifier"
                               :disabled="historyView === 'curve' && !isCurveProperty(item)"
                               :label="`${item.propertyName} (${item.identifier})`"/>
                  </el-select>
                  <label class="text-xs text-gray-600">时间范围 <span class="text-red-500">*</span></label>
                  <el-date-picker v-model="historyRange" type="datetimerange" size="small" class="!w-[330px]"
                                  start-placeholder="开始时间" end-placeholder="结束时间"
                                  :shortcuts="historyShortcuts" :clearable="false"
                                  @change="handlePropertyRangeChange"/>
                  <el-radio-group v-model="historyView" size="small" @change="handleHistoryViewChange">
                    <el-radio-button value="curve" :disabled="!hasCurveProperties">曲线</el-radio-button>
                    <el-radio-button value="raw">原始值</el-radio-button>
                  </el-radio-group>
                  <el-button type="primary" size="small" :loading="historyLoading"
                             @click="querySelectedPropertyHistory">查询
                  </el-button>
                </div>
                <div v-if="historyError" class="px-1 text-xs text-red-500">{{ historyError }}</div>
                <el-alert v-if="!historyAvailable" type="error" :closable="false"
                          :title="historyView === 'curve' ? '属性曲线暂不可用，请稍后重试' : '属性原始值暂不可用，请稍后重试'"/>
                <div v-if="historyView === 'curve'" v-loading="historyLoading"
                     class="rounded border border-gray-200 bg-white">
                  <PropertyHistoryChart v-if="propertyHistory?.points.length" :name="selectedPropertyName"
                                        :history="propertyHistory"/>
                  <el-empty v-else description="所选时间范围暂无曲线数据" :image-size="56" class="h-[280px]"/>
                </div>
                <template v-else>
                  <el-table :data="propertyRawValues" size="small" border v-loading="historyLoading">
                    <el-table-column label="发生时间" width="180">
                      <template #default="{ row }">{{ formatOccurredAt(row.occurredAt) }}</template>
                    </el-table-column>
                    <el-table-column label="原始值" min-width="220" show-overflow-tooltip>
                      <template #default="{ row }"><span class="font-mono text-xs">{{
                          formatLatestValue(row.value)
                        }}</span></template>
                    </el-table-column>
                    <el-table-column label="存储类型" width="90">
                      <template #default="{ row }">{{ propertyValueTypeLabel(row.valueType) }}</template>
                    </el-table-column>
                    <el-table-column prop="msgId" label="消息 ID" min-width="250" show-overflow-tooltip/>
                  </el-table>
                  <div class="flex justify-end">
                    <el-pagination v-model:current-page="propertyRawPageNum" v-model:page-size="propertyRawPageSize"
                                   size="small" layout="total, sizes, prev, pager, next"
                                   :page-sizes="[10, 20, 50, 100]" :total="propertyRawTotal"
                                   @current-change="queryPropertyRawValues"
                                   @size-change="handlePropertyRawPageSizeChange"/>
                  </div>
                </template>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </el-tab-pane>

      <el-tab-pane label="服务" name="services" class="min-h-0 overflow-auto">
        <div class="mt-2 flex h-full min-h-0 flex-col gap-2">
          <el-alert title="服务调用接口待定义；调用表单、请求状态和结果将在此页签呈现。" type="info" :closable="false"/>
          <div class="grid grid-cols-1 gap-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <el-card shadow="never" class="compact-panel">
              <template #header>服务调用</template>
              <el-form label-width="72px" size="small">
                <el-form-item label="服务">
                  <el-select disabled placeholder="待加载服务定义" class="!w-full"/>
                </el-form-item>
                <el-form-item label="调用参数">
                  <el-input disabled type="textarea" :rows="4" placeholder="待服务定义加载后生成参数表单"/>
                </el-form-item>
                <div class="flex justify-end">
                  <el-button type="primary" size="small" disabled>调用</el-button>
                </div>
              </el-form>
            </el-card>
            <el-card shadow="never" class="compact-panel">
              <template #header>调用结果</template>
              <el-empty description="暂无调用结果" :image-size="56"/>
            </el-card>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="事件" name="events" class="min-h-0 overflow-auto">
        <div class="mt-2 flex min-h-0 flex-col gap-2">
          <div class="flex flex-wrap items-center gap-2 rounded border border-gray-200 bg-gray-50 px-2 py-1.5">
            <label class="text-xs text-gray-600">事件 <span class="text-red-500">*</span></label>
            <el-select v-model="eventIdentifier" size="small" class="!w-[220px]" placeholder="选择事件"
                       :clearable="false" @change="searchEvents">
              <el-option v-for="item in eventDefinitions" :key="item.identifier" :value="item.identifier"
                         :label="`${item.eventName} (${item.identifier})`"/>
            </el-select>
            <label class="text-xs text-gray-600">时间范围 <span class="text-red-500">*</span></label>
            <el-date-picker v-model="eventRange" type="datetimerange" size="small" class="!w-[330px]"
                            start-placeholder="开始时间" end-placeholder="结束时间"
                            :shortcuts="eventShortcuts" :clearable="false"/>
            <el-button type="primary" size="small" :loading="eventsLoading" @click="searchEvents">查询</el-button>
          </div>
          <div v-if="eventsError" class="px-1 text-xs text-red-500">{{ eventsError }}</div>
          <el-alert v-if="!eventsAvailable" type="error" :closable="false" title="设备事件暂不可用，请稍后重试"/>
          <el-table :data="deviceEvents" size="small" border v-loading="eventsLoading">
            <el-table-column label="发生时间" width="180">
              <template #default="{ row }">{{ formatOccurredAt(row.occurredAt) }}</template>
            </el-table-column>
            <el-table-column label="等级" width="88" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="eventTypeTag(row.eventType)">{{ eventTypeLabel(row.eventType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column v-for="param in selectedEventParams" :key="param.identifier"
                             :label="param.name" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ formatEventParam(row.params, param.identifier) }}</template>
            </el-table-column>
            <el-table-column prop="msgId" label="消息 ID" min-width="250" show-overflow-tooltip/>
          </el-table>
          <div class="flex justify-end">
            <el-pagination v-model:current-page="eventPageNum" v-model:page-size="eventPageSize"
                           size="small" layout="total, sizes, prev, pager, next"
                           :page-sizes="[10, 20, 50, 100]" :total="eventsTotal"
                           @current-change="queryDeviceEvents" @size-change="handleEventPageSizeChange"/>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="在线历史" name="onlineHistory" class="min-h-0 overflow-auto">
        <div class="mt-2 flex min-h-0 flex-col gap-2">
          <div class="flex flex-wrap items-center gap-2 rounded border border-gray-200 bg-gray-50 px-2 py-1.5">
            <label class="text-xs text-gray-600">时间范围 <span class="text-red-500">*</span></label>
            <el-date-picker v-model="onlineLogRange" type="datetimerange" size="small" class="!w-[330px]"
                            start-placeholder="开始时间" end-placeholder="结束时间"
                            :shortcuts="onlineLogShortcuts" :clearable="false"
                            @change="handleOnlineLogRangeChange"/>
            <el-button type="primary" size="small" :loading="onlineQueryLoading" @click="searchOnlineLogs">
              查询
            </el-button>
          </div>
          <div v-if="onlineLogsError" class="px-1 text-xs text-red-500">{{ onlineLogsError }}</div>
          <el-alert v-if="!onlineLogsAvailable" type="error" :closable="false"
                    title="上下线历史暂不可用，请稍后重试"/>
          <div class="rounded border border-gray-200 bg-white" v-loading="onlineChartLoading">
            <div class="border-b border-gray-100 px-2 py-1.5 text-xs font-medium text-gray-600">在线状态曲线</div>
            <div v-if="onlineChartError" class="px-2 pt-2 text-xs text-red-500">{{ onlineChartError }}</div>
            <OnlineStatusChart v-if="onlineHistoryHasData" :history="onlineHistory"
                               :from="onlineLogRange[0].getTime()" :to="onlineLogRange[1].getTime()"/>
            <el-empty v-else-if="!onlineChartLoading" description="所选时间范围暂无上下线数据"
                      :image-size="48" class="h-[180px]"/>
          </div>
          <el-table :data="onlineLogs" size="small" border v-loading="onlineLogsLoading">
            <el-table-column label="状态" width="105">
              <template #default="{ row }">
                <span class="inline-flex items-center gap-1.5 font-medium">
                  <el-icon :class="onlineEventIconClass(row.event)">
                    <CircleCheckFilled v-if="row.event === 1"/>
                    <CircleCloseFilled v-else/>
                  </el-icon>
                  <span>{{ onlineEventLabel(row.event) }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="发生时间" width="180">
              <template #default="{ row }">{{ formatOccurredAt(row.occurredAt) }}</template>
            </el-table-column>
            <el-table-column label="原因" min-width="180">
              <template #default="{ row }">
                <span>{{ onlineReasonLabel(row.reason) }}</span>
                <span class="ml-1 font-mono text-[11px] text-gray-400">({{ row.reason || '—' }})</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="flex justify-end">
            <el-pagination v-model:current-page="onlineLogPageNum" v-model:page-size="onlineLogPageSize"
                           size="small" layout="total, sizes, prev, pager, next"
                           :page-sizes="[10, 20, 50, 100]" :total="onlineLogsTotal"
                           @current-change="queryDeviceOnlineLogs"
                           @size-change="handleOnlineLogPageSizeChange"/>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import {
  type DeviceEventVO,
  type DeviceOnlineHistoryVO,
  type DeviceOnlineLogVO,
  type DeviceVO,
  getDevice,
  getDeviceLatestProperties,
  getDeviceOnlineHistory,
  getDevicePropertyHistory,
  getProduct,
  type LatestPropertySnapshotVO,
  listDeviceEvents,
  listDeviceOnlineLogs,
  listDevicePropertyRawValues,
  listTmEvents,
  listTmProperties,
  type ProductVO,
  type PropertyHistoryVO,
  type PropertyRawValueVO,
  type TmEventVO,
  type TmPropertyVO,
} from '@/api/device'

const PropertyHistoryChart = defineAsyncComponent(() => import('./PropertyHistoryChart.vue'))
const OnlineStatusChart = defineAsyncComponent(() => import('./OnlineStatusChart.vue'))

type TemplateField = { key: string; label: string }

const MAX_QUERY_RANGE_MS = 7 * 24 * 60 * 60 * 1000

const route = useRoute()
const router = useRouter()
const activeTab = ref('overview')
const device = ref<DeviceVO>()
const product = ref<ProductVO>()
const latestProperties = ref<LatestPropertySnapshotVO>()
const latestPropertiesLoading = ref(false)
let latestPropertiesDeviceId: number | undefined
const historyProperties = ref<TmPropertyVO[]>([])
const selectedIdentifier = ref('')
const historyView = ref<'curve' | 'raw'>('curve')
const historyRange = ref<[Date, Date]>([new Date(Date.now() - 60 * 60 * 1000), new Date()])
const propertyHistory = ref<PropertyHistoryVO>()
const propertyRawValues = ref<PropertyRawValueVO[]>([])
const propertyRawPageNum = ref(1)
const propertyRawPageSize = ref(20)
const propertyRawTotal = ref(0)
const historyLoading = ref(false)
const historyAvailable = ref(true)
const historyError = ref('')
const propertyPanels = ref<string[]>([])
let historyPropertiesProductId: number | undefined
let historyRequestSequence = 0
const eventDefinitions = ref<TmEventVO[]>([])
const eventIdentifier = ref('')
const eventRange = ref<[Date, Date]>([new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()])
const deviceEvents = ref<DeviceEventVO[]>([])
const eventPageNum = ref(1)
const eventPageSize = ref(20)
const eventsTotal = ref(0)
const eventsLoading = ref(false)
const eventsAvailable = ref(true)
const eventsError = ref('')
let eventsDeviceId: number | undefined
let eventRequestSequence = 0
const onlineLogRange = ref<[Date, Date]>([new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()])
const onlineLogs = ref<DeviceOnlineLogVO[]>([])
const onlineLogPageNum = ref(1)
const onlineLogPageSize = ref(20)
const onlineLogsTotal = ref(0)
const onlineLogsLoading = ref(false)
const onlineLogsAvailable = ref(true)
const onlineLogsError = ref('')
let onlineLogsDeviceId: number | undefined
let onlineLogRequestSequence = 0
const onlineHistory = ref<DeviceOnlineHistoryVO>()
const onlineChartLoading = ref(false)
const onlineChartError = ref('')
let onlineChartRequestSequence = 0

const historyShortcuts = [
  {text: '最近15分钟', value: () => [new Date(Date.now() - 15 * 60 * 1000), new Date()]},
  {text: '最近1小时', value: () => [new Date(Date.now() - 60 * 60 * 1000), new Date()]},
  {text: '最近6小时', value: () => [new Date(Date.now() - 6 * 60 * 60 * 1000), new Date()]},
  {text: '最近24小时', value: () => [new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()]},
]
const eventShortcuts = [
  {text: '最近1小时', value: () => recentRange(60 * 60 * 1000)},
  {text: '最近6小时', value: () => recentRange(6 * 60 * 60 * 1000)},
  {text: '最近24小时', value: () => recentRange(24 * 60 * 60 * 1000)},
  {text: '最近7天', value: () => recentRange(7 * 24 * 60 * 60 * 1000)},
]
const onlineLogShortcuts = [
  {text: '最近1小时', value: () => recentRange(60 * 60 * 1000)},
  {text: '最近6小时', value: () => recentRange(6 * 60 * 60 * 1000)},
  {text: '最近24小时', value: () => recentRange(24 * 60 * 60 * 1000)},
  {text: '最近7天', value: () => recentRange(7 * 24 * 60 * 60 * 1000)},
]

const selectedPropertyName = computed(() => {
  const property = historyProperties.value.find(item => item.identifier === selectedIdentifier.value)
  return property?.propertyName || selectedIdentifier.value || '属性'
})
const historyExpanded = computed(() => propertyPanels.value.includes('history'))
const onlineHistoryHasData = computed(() => Boolean(
    onlineHistory.value?.initialEvent || onlineHistory.value?.points.length))
const onlineQueryLoading = computed(() => onlineLogsLoading.value || onlineChartLoading.value)
const hasCurveProperties = computed(() => historyProperties.value.some(isCurveProperty))
const selectedEventParams = computed(() => {
  const definition = eventDefinitions.value.find(item => item.identifier === eventIdentifier.value)
  return (definition?.inputParams || []).map(param => ({
    identifier: String(param.identifier || ''),
    name: String(param.name || param.identifier || ''),
  })).filter(param => param.identifier)
})

const templateFields = computed<TemplateField[]>(() => {
  const groups = (product.value?.deviceFormSchema?.groups as Array<{ fields?: TemplateField[] }> | undefined) || []
  return groups.flatMap(group => group.fields || []).filter(field => field.key)
})

function statusLabel(status: number) {
  return ({0: '未激活', 1: '在线', 2: '离线', 3: '未知'} as Record<number, string>)[status] || String(status)
}

// 3-未知 用 warning（橙）：它表示平台失去观测能力（驱动失联），
// 不是设备确认故障，用 danger 会与真实离线混为一谈
function statusType(status: number): 'info' | 'success' | 'danger' | 'warning' {
  return ({0: 'info', 1: 'success', 2: 'danger', 3: 'warning'} as const)[status as 0 | 1 | 2 | 3] || 'info'
}

function deviceStatusIconClass(status: number) {
  return ({
    0: 'device-status--inactive',
    1: 'device-status--online',
    2: 'device-status--offline',
    3: 'device-status--unknown'
  } as Record<number, string>)[status] || 'device-status--inactive'
}

function onlineEventLabel(event: number) {
  return event === 1 ? '上线' : event === 2 ? '离线' : String(event)
}

function onlineEventIconClass(event: number) {
  return event === 1 ? 'device-status--online' : 'device-status--offline'
}

function onlineReasonLabel(reason: string) {
  return ({
    connect: '设备连接',
    heartbeat_timeout: '心跳超时',
    lwt: '遗嘱离线',
    disconnect: '主动断开',
  } as Record<string, string>)[reason] || '其他'
}

function nodeTypeLabel(type: number) {
  return ({1: '直连', 2: '网关', 3: '子设备'} as Record<number, string>)[type] || String(type)
}

function formatLatestValue(value: unknown) {
  return typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? '—')
}

function formatOccurredAt(timestamp: number) {
  return Number.isFinite(timestamp) ? new Date(timestamp).toLocaleString() : '—'
}

function recentRange(duration: number): [Date, Date] {
  const now = Date.now()
  return [new Date(now - duration), new Date(now)]
}

function eventTypeLabel(type: number) {
  return ({1: '信息', 2: '告警', 3: '故障'} as Record<number, string>)[type] || String(type)
}

function eventTypeTag(type: number): 'info' | 'warning' | 'danger' {
  return ({1: 'info', 2: 'warning', 3: 'danger'} as const)[type as 1 | 2 | 3] || 'info'
}

function formatEventParam(params: Record<string, unknown> | undefined, identifier: string) {
  const value = params?.[identifier] ?? params?.[identifier.toLowerCase()]
  if (value === undefined || value === null || value === '') return '—'
  return formatLatestValue(value)
}

function isCurveProperty(property: TmPropertyVO) {
  return ['int', 'float', 'double', 'bool'].includes(property.dataType)
}

function propertyValueTypeLabel(type: number) {
  return ({1: '布尔', 2: '整数', 3: '浮点', 4: '文本'} as Record<number, string>)[type] || String(type)
}

function handlePropertySelectionChange() {
  propertyRawPageNum.value = 1
  void querySelectedPropertyHistory()
}

function handlePropertyRangeChange() {
  propertyRawPageNum.value = 1
  void querySelectedPropertyHistory()
}

function handleHistoryViewChange() {
  if (historyView.value === 'curve') {
    const selected = historyProperties.value.find(item => item.identifier === selectedIdentifier.value)
    if (!selected || !isCurveProperty(selected)) {
      selectedIdentifier.value = historyProperties.value.find(isCurveProperty)?.identifier || ''
    }
  } else if (!selectedIdentifier.value && historyProperties.value.length) {
    selectedIdentifier.value = historyProperties.value[0].identifier
  }
  propertyRawPageNum.value = 1
  historyError.value = ''
  historyAvailable.value = true
  void querySelectedPropertyHistory()
}

function querySelectedPropertyHistory() {
  return historyView.value === 'curve' ? queryPropertyHistory() : queryPropertyRawValues()
}

function handlePropertyRawPageSizeChange() {
  propertyRawPageNum.value = 1
  void queryPropertyRawValues()
}

async function loadDetail() {
  const deviceId = Number(route.params.deviceId)
  if (!Number.isInteger(deviceId) || deviceId <= 0) return
  latestProperties.value = undefined
  latestPropertiesDeviceId = undefined
  historyProperties.value = []
  historyPropertiesProductId = undefined
  selectedIdentifier.value = ''
  historyView.value = 'curve'
  propertyHistory.value = undefined
  propertyRawValues.value = []
  propertyRawPageNum.value = 1
  propertyRawTotal.value = 0
  historyAvailable.value = true
  historyError.value = ''
  propertyPanels.value = []
  historyRange.value = [new Date(Date.now() - 60 * 60 * 1000), new Date()]
  historyRequestSequence++
  eventDefinitions.value = []
  eventIdentifier.value = ''
  eventRange.value = [new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()]
  deviceEvents.value = []
  eventPageNum.value = 1
  eventsTotal.value = 0
  eventsAvailable.value = true
  eventsError.value = ''
  eventsDeviceId = undefined
  eventRequestSequence++
  onlineLogRange.value = [new Date(Date.now() - 24 * 60 * 60 * 1000), new Date()]
  onlineLogs.value = []
  onlineLogPageNum.value = 1
  onlineLogsTotal.value = 0
  onlineLogsLoading.value = false
  onlineLogsAvailable.value = true
  onlineLogsError.value = ''
  onlineLogsDeviceId = undefined
  onlineLogRequestSequence++
  onlineHistory.value = undefined
  onlineChartLoading.value = false
  onlineChartError.value = ''
  onlineChartRequestSequence++
  const deviceRes = await getDevice(deviceId)
  if (!deviceRes.data) return
  device.value = deviceRes.data
  const productRes = await getProduct(deviceRes.data.productId)
  product.value = productRes.data
  if (activeTab.value === 'onlineHistory') void loadOnlineLogs()
  if (activeTab.value === 'properties') void loadPropertyTab()
  if (activeTab.value === 'events') void loadEventTab()
}

async function loadOnlineLogs() {
  const deviceId = Number(route.params.deviceId)
  if (!Number.isInteger(deviceId) || deviceId <= 0 || onlineLogsDeviceId === deviceId) return
  onlineLogsDeviceId = deviceId
  await Promise.all([queryDeviceOnlineLogs(), queryOnlineStatusHistory()])
}

function searchOnlineLogs() {
  onlineLogPageNum.value = 1
  void Promise.all([queryDeviceOnlineLogs(), queryOnlineStatusHistory()])
}

function handleOnlineLogRangeChange() {
  searchOnlineLogs()
}

function handleOnlineLogPageSizeChange() {
  onlineLogPageNum.value = 1
  void queryDeviceOnlineLogs()
}

async function queryDeviceOnlineLogs() {
  const deviceId = Number(route.params.deviceId)
  const range = onlineLogRange.value
  if (!Number.isInteger(deviceId) || deviceId <= 0 || !range?.[0] || !range?.[1]) return
  if (range[1].getTime() <= range[0].getTime()) {
    onlineLogsError.value = '结束时间必须晚于开始时间'
    return
  }
  if (range[1].getTime() - range[0].getTime() > MAX_QUERY_RANGE_MS) {
    onlineLogsError.value = '单次查询时间范围不能超过 7 天，请分段查询'
    return
  }
  const requestSequence = ++onlineLogRequestSequence
  onlineLogsLoading.value = true
  onlineLogsAvailable.value = true
  onlineLogsError.value = ''
  try {
    const response = await listDeviceOnlineLogs(deviceId, {
      pageNum: onlineLogPageNum.value,
      pageSize: onlineLogPageSize.value,
      query: {from: range[0].getTime(), to: range[1].getTime()},
    })
    if (Number(route.params.deviceId) === deviceId && onlineLogRequestSequence === requestSequence) {
      onlineLogs.value = response.data?.list || []
      onlineLogsTotal.value = response.data?.total || 0
      onlineLogsDeviceId = deviceId
    }
  } catch (error) {
    if (onlineLogRequestSequence === requestSequence) {
      onlineLogs.value = []
      onlineLogsTotal.value = 0
      onlineLogsAvailable.value = false
      onlineLogsError.value = error instanceof Error ? error.message : '上下线历史查询失败'
      onlineLogsDeviceId = undefined
    }
  } finally {
    if (onlineLogRequestSequence === requestSequence) onlineLogsLoading.value = false
  }
}

async function queryOnlineStatusHistory() {
  const deviceId = Number(route.params.deviceId)
  const range = onlineLogRange.value
  if (!Number.isInteger(deviceId) || deviceId <= 0 || !range?.[0] || !range?.[1]) return
  if (range[1].getTime() <= range[0].getTime()) {
    onlineChartError.value = '结束时间必须晚于开始时间'
    return
  }
  if (range[1].getTime() - range[0].getTime() > MAX_QUERY_RANGE_MS) {
    onlineChartError.value = '单次查询时间范围不能超过 7 天，请分段查询'
    return
  }
  const requestSequence = ++onlineChartRequestSequence
  onlineChartLoading.value = true
  onlineChartError.value = ''
  try {
    const response = await getDeviceOnlineHistory(deviceId, range[0].getTime(), range[1].getTime())
    if (Number(route.params.deviceId) === deviceId && onlineChartRequestSequence === requestSequence) {
      onlineHistory.value = response.data
    }
  } catch (error) {
    if (onlineChartRequestSequence === requestSequence) {
      onlineHistory.value = undefined
      onlineChartError.value = error instanceof Error ? error.message : '在线状态曲线查询失败'
    }
  } finally {
    if (onlineChartRequestSequence === requestSequence) onlineChartLoading.value = false
  }
}

async function loadHistoryProperties() {
  if (!device.value || historyPropertiesProductId === device.value.productId) return
  const productId = device.value.productId
  historyPropertiesProductId = productId
  try {
    const response = await listTmProperties(productId)
    if (device.value?.productId !== productId) return
    historyProperties.value = response.data || []
    if (!selectedIdentifier.value && historyProperties.value.length) {
      const curveProperty = historyProperties.value.find(isCurveProperty)
      if (curveProperty) {
        selectedIdentifier.value = curveProperty.identifier
      } else {
        historyView.value = 'raw'
        selectedIdentifier.value = historyProperties.value[0].identifier
      }
      await querySelectedPropertyHistory()
    }
  } catch {
    historyPropertiesProductId = undefined
  }
}

async function queryPropertyHistory() {
  const deviceId = Number(route.params.deviceId)
  const range = historyRange.value
  if (!Number.isInteger(deviceId) || deviceId <= 0 || !selectedIdentifier.value || !range?.[0] || !range?.[1]) return
  if (range[1].getTime() <= range[0].getTime()) {
    historyError.value = '结束时间必须晚于开始时间'
    return
  }
  if (range[1].getTime() - range[0].getTime() > MAX_QUERY_RANGE_MS) {
    historyError.value = '单次查询时间范围不能超过 7 天，请分段查询'
    return
  }
  const selected = historyProperties.value.find(item => item.identifier === selectedIdentifier.value)
  if (!selected || !isCurveProperty(selected)) {
    historyError.value = '该属性类型不支持曲线，请切换到原始值'
    propertyHistory.value = undefined
    return
  }
  const requestSequence = ++historyRequestSequence
  historyLoading.value = true
  historyAvailable.value = true
  historyError.value = ''
  try {
    const response = await getDevicePropertyHistory(
        deviceId, selectedIdentifier.value, range[0].getTime(), range[1].getTime())
    if (Number(route.params.deviceId) === deviceId && historyRequestSequence === requestSequence) {
      propertyHistory.value = response.data
      historyAvailable.value = true
    }
  } catch (error) {
    if (historyRequestSequence === requestSequence) {
      propertyHistory.value = undefined
      historyAvailable.value = false
      historyError.value = error instanceof Error ? error.message : '属性曲线查询失败'
    }
  } finally {
    if (historyRequestSequence === requestSequence) historyLoading.value = false
  }
}

async function queryPropertyRawValues() {
  const deviceId = Number(route.params.deviceId)
  const range = historyRange.value
  if (!Number.isInteger(deviceId) || deviceId <= 0 || !selectedIdentifier.value || !range?.[0] || !range?.[1]) return
  if (range[1].getTime() <= range[0].getTime()) {
    historyError.value = '结束时间必须晚于开始时间'
    return
  }
  if (range[1].getTime() - range[0].getTime() > MAX_QUERY_RANGE_MS) {
    historyError.value = '单次查询时间范围不能超过 7 天，请分段查询'
    return
  }
  const requestSequence = ++historyRequestSequence
  historyLoading.value = true
  historyAvailable.value = true
  historyError.value = ''
  try {
    const response = await listDevicePropertyRawValues(deviceId, selectedIdentifier.value, {
      pageNum: propertyRawPageNum.value,
      pageSize: propertyRawPageSize.value,
      query: {from: range[0].getTime(), to: range[1].getTime()},
    })
    if (Number(route.params.deviceId) === deviceId && historyRequestSequence === requestSequence) {
      propertyRawValues.value = response.data?.list || []
      propertyRawTotal.value = response.data?.total || 0
    }
  } catch (error) {
    if (historyRequestSequence === requestSequence) {
      propertyRawValues.value = []
      propertyRawTotal.value = 0
      historyAvailable.value = false
      historyError.value = error instanceof Error ? error.message : '属性原始值查询失败'
    }
  } finally {
    if (historyRequestSequence === requestSequence) historyLoading.value = false
  }
}

async function loadPropertyTab() {
  await loadLatestProperties()
  if (historyExpanded.value) await loadHistoryProperties()
}

function handlePropertyCollapseChange(names: string[] | string) {
  const activeNames = Array.isArray(names) ? names : [names]
  if (activeNames.includes('history')) void loadHistoryProperties()
}

async function loadLatestProperties() {
  const deviceId = Number(route.params.deviceId)
  if (!Number.isInteger(deviceId) || deviceId <= 0 || latestPropertiesDeviceId === deviceId) return
  latestPropertiesDeviceId = deviceId
  latestPropertiesLoading.value = true
  try {
    const response = await getDeviceLatestProperties(deviceId)
    if (Number(route.params.deviceId) === deviceId) latestProperties.value = response.data
  } catch {
    if (Number(route.params.deviceId) === deviceId) {
      latestProperties.value = {available: false, properties: []}
    }
  } finally {
    latestPropertiesLoading.value = false
  }
}

async function loadEventTab() {
  const deviceId = Number(route.params.deviceId)
  if (!device.value || !Number.isInteger(deviceId) || deviceId <= 0 || eventsDeviceId === deviceId) return
  eventsDeviceId = deviceId
  try {
    const definitions = await listTmEvents(device.value.productId)
    if (Number(route.params.deviceId) !== deviceId) return
    eventDefinitions.value = definitions.data || []
    if (!eventIdentifier.value && eventDefinitions.value.length) {
      eventIdentifier.value = eventDefinitions.value[0].identifier
    }
    if (eventIdentifier.value) await queryDeviceEvents()
  } catch (error) {
    if (Number(route.params.deviceId) !== deviceId) return
    eventsDeviceId = undefined
    eventsAvailable.value = false
    eventsError.value = error instanceof Error ? error.message : '设备事件查询失败'
  }
}

function searchEvents() {
  eventPageNum.value = 1
  void queryDeviceEvents()
}

function handleEventPageSizeChange() {
  eventPageNum.value = 1
  void queryDeviceEvents()
}

async function queryDeviceEvents() {
  const deviceId = Number(route.params.deviceId)
  const range = eventRange.value
  if (!eventIdentifier.value) {
    eventsError.value = '请选择事件定义'
    deviceEvents.value = []
    eventsTotal.value = 0
    return
  }
  if (!Number.isInteger(deviceId) || deviceId <= 0 || !range?.[0] || !range?.[1]) return
  if (range[1].getTime() <= range[0].getTime()) {
    eventsError.value = '结束时间必须晚于开始时间'
    return
  }
  if (range[1].getTime() - range[0].getTime() > MAX_QUERY_RANGE_MS) {
    eventsError.value = '单次查询时间范围不能超过 7 天，请分段查询'
    return
  }
  const requestSequence = ++eventRequestSequence
  eventsLoading.value = true
  eventsAvailable.value = true
  eventsError.value = ''
  try {
    const response = await listDeviceEvents(deviceId, {
      pageNum: eventPageNum.value,
      pageSize: eventPageSize.value,
      query: {
        identifier: eventIdentifier.value,
        from: range[0].getTime(),
        to: range[1].getTime(),
      },
    })
    if (Number(route.params.deviceId) === deviceId && eventRequestSequence === requestSequence) {
      deviceEvents.value = response.data?.list || []
      eventsTotal.value = response.data?.total || 0
    }
  } catch (error) {
    if (eventRequestSequence === requestSequence) {
      deviceEvents.value = []
      eventsTotal.value = 0
      eventsAvailable.value = false
      eventsError.value = error instanceof Error ? error.message : '设备事件查询失败'
    }
  } finally {
    if (eventRequestSequence === requestSequence) eventsLoading.value = false
  }
}

watch(() => route.params.deviceId, loadDetail, {immediate: true})
watch(activeTab, tab => {
  if (tab === 'onlineHistory') void loadOnlineLogs()
  if (tab === 'properties') void loadPropertyTab()
  if (tab === 'events') void loadEventTab()
})
</script>

<style scoped>
.device-detail-tabs :deep(.el-tabs__content) {
  min-height: 0;
  flex: 1;
}

.device-detail-tabs :deep(.el-tab-pane) {
  height: 100%;
}

.property-history-collapse :deep(.el-collapse-item__header) {
  height: 34px;
  padding: 0 8px;
}

.property-history-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

.device-status--online {
  color: var(--el-color-success);
}

.device-status--offline {
  color: var(--el-color-danger);
}

.device-status--unknown {
  color: var(--el-color-warning);
}

.device-status--inactive {
  color: var(--el-text-color-secondary);
}
</style>

<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white p-3">
    <el-form
        :model="params"
        inline
        size="small"
        class="compact-query-form mb-2 shrink-0"
    >
      <el-form-item
          label="产品"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.productId"
            filterable
            remote
            clearable
            placeholder="选择普通产品"
            class="!w-[180px]"
            :remote-method="searchProducts"
            @change="onProductChange"
        >
          <el-option
              v-for="p in productOptions"
              :key="p.productId"
              :label="`${p.productName} (${p.productKey})`"
              :value="p.productId"
          />
        </el-select>
      </el-form-item>
      <el-form-item
          label="编码"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.deviceCode"
            clearable
            class="!w-[120px]"
        />
      </el-form-item>
      <el-form-item
          label="名称"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.deviceName"
            clearable
            class="!w-[120px]"
        />
      </el-form-item>
      <el-form-item
          label="状态"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.status"
            clearable
            placeholder="全部"
            class="!w-[100px]"
        >
          <el-option
              label="未激活"
              :value="0"
          />
          <el-option
              label="在线"
              :value="1"
          />
          <el-option
              label="离线"
              :value="2"
          />
          <el-option
              label="未知"
              :value="3"
          />
        </el-select>
      </el-form-item>
      <div class="flex gap-1 items-center !ml-auto">
        <el-button
            v-hasPermi="['iot:device:list']"
            type="primary"
            size="small"
            @click="handleQuery"
        >
          搜索
        </el-button>
        <el-button
            size="small"
            @click="handleReset"
        >
          重置
        </el-button>
      </div>
      <div v-if="searchableFields.length" class="basis-full flex flex-wrap items-center gap-2">
        <el-form-item
            v-for="f in searchableFields"
            :key="f.key"
            :label="f.label"
            class="!mb-0"
        >
          <el-select v-if="f.type === 'bool'" v-model="filterFor(f.key).value" clearable class="!w-[110px]">
            <el-option label="是" value="true"/>
            <el-option label="否" value="false"/>
          </el-select>
          <el-select v-else-if="f.type === 'enum'" v-model="filterFor(f.key).value" clearable class="!w-[110px]">
            <el-option v-for="option in f.options || []" :key="String(option)" :label="String(option)"
                       :value="String(option)"/>
          </el-select>
          <div v-else-if="f.type === 'int' || f.type === 'float'" class="flex items-center gap-1">
            <el-select v-model="filterFor(f.key).operator" class="!w-[70px]" placeholder="比较">
              <el-option label=">" value="GT"/>
              <el-option label=">=" value="GE"/>
              <el-option label="=" value="EQ"/>
              <el-option label="!=" value="NE"/>
              <el-option label="<=" value="LE"/>
              <el-option label="<" value="LT"/>
            </el-select>
            <el-input-number v-model="filterFor(f.key).number" :precision="f.type === 'int' ? 0 : undefined"
                             :step="f.type === 'int' ? 1 : 0.1" controls-position="right" class="!w-[112px]"
                             placeholder="数值"/>
          </div>
          <el-input v-else v-model="filterFor(f.key).value" clearable class="!w-[110px]" placeholder="包含匹配"/>
        </el-form-item>
      </div>
    </el-form>

    <div class="compact-action-toolbar">
      <el-button v-hasPermi="['iot:device:add']" type="primary" size="small"
                 :disabled="!params.query!.productId" @click="openCreate">
        <el-icon>
          <Plus/>
        </el-icon>
        新建设备
      </el-button>
      <div class="ml-auto flex items-center gap-2">
        <span class="text-[11px] text-gray-400">共 {{ total }} 条设备</span>
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button value="card">卡片</el-radio-button>
          <el-radio-button value="table">表格</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div
        v-if="viewMode === 'table'"
        class="compact-table-region"
    >
      <el-table
          v-loading="loading"
          :data="list"
          border
          stripe
          size="small"
          height="100%"
          style="width: 100%"
          @row-click="openDetail"
      >
        <el-table-column label="所属产品" min-width="180">
          <template #default="{ row }">
            <div class="flex items-center gap-2 min-w-0">
              <div class="w-6 h-6 rounded bg-gray-100 flex items-center justify-center shrink-0 overflow-hidden">
                <el-icon :size="14">
                  <component :is="getProductIcon(row.icon)"/>
                </el-icon>
              </div>
              <div class="min-w-0 truncate text-[12px]">{{ row.productName || '—' }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column
            prop="deviceCode"
            label="设备编码"
            min-width="120"
        />
        <el-table-column
            prop="deviceName"
            label="名称"
            min-width="100"
        />
        <el-table-column
            label="节点"
            width="80"
            align="center"
        >
          <template #default="{ row }">
            {{ nodeTypeLabel(row.nodeType) }}
          </template>
        </el-table-column>
        <el-table-column
            label="状态"
            width="80"
            align="center"
        >
          <template #default="{ row }">
            <el-tag
                size="small"
                :type="statusType(row.status)"
            >
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
            prop="lastOnlineAt"
            label="最近上线"
            min-width="140"
        />
        <el-table-column
            prop="address"
            label="地址"
            min-width="120"
        />
        <el-table-column
            v-for="f in listVisibleFields"
            :key="f.key"
            :label="f.label"
            min-width="100"
        >
          <template #default="{ row }">
            {{ row.deviceFormData?.[f.key] ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column
            label="操作"
            width="110"
            fixed="right"
            align="center"
        >
          <template #default="{ row }">
            <el-tooltip
                content="编辑"
                placement="top"
                :enterable="false"
            >
              <el-button
                  v-hasPermi="['iot:device:edit']"
                  type="primary"
                  link
                  size="small"
                  class="!p-1"
                  @click.stop="openEdit(row)"
              >
                <el-icon>
                  <Edit/>
                </el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip
                content="删除"
                placement="top"
                :enterable="false"
            >
              <el-button
                  v-hasPermi="['iot:device:remove']"
                  type="danger"
                  link
                  size="small"
                  class="!p-1"
                  @click.stop="handleDelete(row)"
              >
                <el-icon>
                  <Delete/>
                </el-icon>
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div
        v-else
        v-loading="loading"
        class="flex-1 min-h-0 overflow-auto grid content-start gap-2"
        style="grid-template-columns: repeat(auto-fill, 192px);"
    >
      <div
          v-for="row in list"
          :key="row.deviceId"
          class="relative h-[90px] cursor-pointer rounded border-2 p-2 transition-colors"
          :class="deviceCardStatusClass(row.status)"
          @click="openDetail(row)"
      >
        <div class="grid grid-cols-[36px_48px_72px] items-start gap-2">
          <div class="flex h-9 w-9 items-center justify-center rounded bg-white/80">
            <el-icon :size="20">
              <component :is="getProductIcon(row.icon)"/>
            </el-icon>
          </div>
          <div class="min-w-0 pt-0.5">
            <div class="truncate text-[13px] font-medium text-gray-800">{{ row.deviceName || row.deviceCode }}</div>
            <div class="truncate font-mono text-[11px] text-gray-500">{{ row.deviceCode }}</div>
          </div>
          <img v-if="row.iconUrl" :src="row.iconUrl"
               class="h-[72px] w-[72px] rounded bg-gray-100 object-cover" alt="">
        </div>
        <div class="absolute bottom-1 left-2 flex items-center gap-1" @click.stop>
          <el-button
              v-hasPermi="['iot:device:edit']"
              type="primary"
              link
              size="small"
              class="!p-1"
              aria-label="编辑"
              @click="openEdit(row)"
          >
            <el-icon>
              <Edit/>
            </el-icon>
          </el-button>
          <el-button
              v-hasPermi="['iot:device:remove']"
              type="danger"
              link
              size="small"
              class="!p-1"
              aria-label="删除"
              @click="handleDelete(row)"
          >
            <el-icon>
              <Delete/>
            </el-icon>
          </el-button>
        </div>
      </div>
    </div>

    <div class="compact-pagination">
      <el-pagination
          v-model:current-page="params.pageNum"
          v-model:page-size="params.pageSize"
          :total="total"
          :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
          layout="total, prev, pager, next, sizes"
          size="small"
          @size-change="loadData"
          @current-change="loadData"
      />
    </div>

    <el-dialog
        v-model="dialogVisible"
        :title="form.deviceId ? '编辑设备' : '新建设备'"
        width="520px"
        :close-on-click-modal="false"
        class="compact-edit-dialog"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="formRules"
          label-width="100px"
          label-position="right"
          size="small"
          class="compact-edit-form"
      >
        <el-form-item label="所属产品" required>
          <el-input :model-value="selectedProductLabel" disabled class="!w-full"/>
        </el-form-item>
        <el-form-item
            label="设备编码"
            prop="deviceCode"
            required
        >
          <el-input
              v-model="form.deviceCode"
              maxlength="50"
              class="!w-full"
              :disabled="!!form.deviceId"
              placeholder="请输入设备编码"
          />
        </el-form-item>
        <el-form-item label="设备名称">
          <el-input
              v-model="form.deviceName"
              maxlength="100"
              class="!w-full"
              placeholder="展示名，可改"
          />
        </el-form-item>
        <el-form-item label="节点类型" prop="nodeType" required>
          <el-select
              v-model="form.nodeType"
              class="!w-full"
          >
            <el-option
                label="直连"
                :value="1"
            />
            <el-option
                label="网关"
                :value="2"
            />
            <el-option
                label="子设备"
                :value="3"
            />
          </el-select>
        </el-form-item>
        <el-form-item
            v-if="form.nodeType === 3"
            label="所属网关"
            prop="gatewayId"
            required
        >
          <el-input
              v-model="gatewayIdText"
              class="!w-full"
              placeholder="网关设备 ID"
          />
        </el-form-item>
        <el-form-item label="安装地址">
          <el-input
              v-model="form.address"
              maxlength="255"
              class="!w-full"
              placeholder="平台侧地址，可空"
          />
        </el-form-item>
        <el-form-item label="经度" prop="longitude">
          <el-input-number v-model="form.longitude" :min="-180" :max="180" :precision="7" :step="0.0000001"
                           controls-position="right" class="!w-full" placeholder="-180 至 180"/>
        </el-form-item>
        <el-form-item label="纬度" prop="latitude">
          <el-input-number v-model="form.latitude" :min="-90" :max="90" :precision="7" :step="0.0000001"
                           controls-position="right" class="!w-full" placeholder="-90 至 90"/>
        </el-form-item>
        <template v-for="group in formSchemaGroups" :key="group.key">
          <div class="text-[12px] font-medium text-gray-600">
            {{ group.label }}
          </div>
          <el-form-item
              v-for="f in group.fields"
              :key="f.key"
              :label="f.label"
              :prop="`deviceFormData.${f.key}`"
              :required="!!f.required && (!form.deviceId || !f.sensitive)"
          >
            <el-select v-if="f.type === 'enum'" v-model="formDataValues[f.key]" class="!w-full" clearable>
              <el-option v-for="option in f.options || []" :key="String(option)" :label="String(option)"
                         :value="option"/>
            </el-select>
            <el-switch v-else-if="f.type === 'bool'" v-model="formDataValues[f.key]"/>
            <el-input-number v-else-if="f.type === 'int' || f.type === 'float'" v-model="formDataValues[f.key]"
                             :precision="f.type === 'int' ? 0 : undefined" controls-position="right" class="!w-full"/>
            <el-input v-else v-model="formDataValues[f.key]" class="!w-full"
                      :type="f.type === 'password' || f.sensitive ? 'password' : f.type === 'text' ? 'textarea' : 'text'"
                      :rows="f.type === 'text' ? 2 : undefined" :show-password="f.type === 'password' || !!f.sensitive"
                      :placeholder="f.sensitive ? '不改可留空' : ''"/>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button
              size="small"
              @click="dialogVisible = false"
          >
            取消
          </el-button>
          <el-button
              size="small"
              type="primary"
              @click="handleSubmit"
          >
            确定
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  createDevice,
  deleteDevice,
  type DeviceFormFilter,
  type DeviceQuery,
  type DeviceVO,
  getDevice,
  getProduct,
  listDevices,
  listProducts,
  type ProductVO,
  updateDevice
} from '@/api/device'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'
import {getProductIcon} from '@/components/device/product-icons'

interface SchemaField {
  key: string
  label: string
  type?: string
  required?: boolean
  searchable?: boolean
  sensitive?: boolean
  listVisible?: boolean
  default?: unknown
  options?: unknown[]
}

interface SchemaGroup {
  key: string
  label: string
  fields: SchemaField[]
}

const VIEW_KEY = 'iot.device.viewMode'
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const list = ref<DeviceVO[]>([])
const total = ref(0)
const productOptions = ref<ProductVO[]>([])
const currentSchema = ref<Record<string, unknown> | null>(null)
const formSchema = ref<Record<string, unknown> | null>(null)
const formProduct = ref<ProductVO>()
const formFilterValues = reactive<Record<string, DeviceFormFilter>>({})
const viewMode = ref<'table' | 'card'>((localStorage.getItem(VIEW_KEY) as 'table' | 'card') || 'table')

watch(viewMode, (v) => localStorage.setItem(VIEW_KEY, v))

const params = reactive<PageRequest<DeviceQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    productId: route.query.productId ? Number(route.query.productId) : undefined,
    deviceCode: '',
    deviceName: '',
    status: undefined,
    formFilters: {}
  }
})

const searchableFields = computed(() => allFormFields.value.filter((f) => f.searchable && !f.sensitive && f.type !== 'password'))
const listVisibleFields = computed(() => allFormFields.value.filter((f) => f.listVisible))

function fieldsOf(groups: SchemaGroup[]) {
  const fields: SchemaField[] = []
  for (const g of groups) {
    for (const f of g.fields || []) {
      if (f.key) fields.push(f)
    }
  }
  return fields
}

function parseSchemaGroups(schema: Record<string, unknown> | null): SchemaGroup[] {
  const rawGroups = schema?.groups
  if (!Array.isArray(rawGroups)) return []
  return rawGroups
      .filter((group): group is Record<string, unknown> => !!group && typeof group === 'object')
      .map(group => ({
        key: String(group.key || ''),
        label: String(group.label || ''),
        fields: Array.isArray(group.fields)
            ? group.fields.filter((field): field is SchemaField => !!field && typeof field === 'object' && !!(field as SchemaField).key)
            : []
      }))
      .filter(group => group.key && group.label && group.fields.length > 0)
}

const schemaGroups = computed(() => parseSchemaGroups(currentSchema.value))
const allFormFields = computed(() => fieldsOf(schemaGroups.value))
const formSchemaGroups = computed(() => parseSchemaGroups(formSchema.value))
const formFields = computed(() => fieldsOf(formSchemaGroups.value))

function nodeTypeLabel(t: number) {
  return ({1: '直连', 2: '网关', 3: '子设备'} as Record<number, string>)[t] || String(t)
}

function statusLabel(s: number) {
  return ({0: '未激活', 1: '在线', 2: '离线', 3: '未知'} as Record<number, string>)[s] || String(s)
}

// 3-未知 用 warning（橙）：它表示平台失去观测能力（驱动失联），
// 不是设备确认故障，用 danger 会与真实离线混为一谈
function statusType(s: number): 'info' | 'success' | 'danger' | 'warning' {
  return ({0: 'info', 1: 'success', 2: 'danger', 3: 'warning'} as const)[s as 0 | 1 | 2 | 3] || 'info'
}

function deviceCardStatusClass(status: number) {
  return ({
    0: 'border-gray-400 bg-gray-100 hover:border-gray-500',
    1: 'border-green-500 bg-green-100 hover:border-green-600',
    2: 'border-red-500 bg-red-100 hover:border-red-600',
    3: 'border-orange-500 bg-orange-100 hover:border-orange-600'
  } as Record<number, string>)[status] || 'border-gray-400 bg-gray-100 hover:border-gray-500'
}

async function searchProducts(keyword: string) {
  const res = await listProducts({
    pageNum: 1,
    pageSize: 50,
    query: {productType: 1, keyword: keyword || undefined}
  })
  productOptions.value = res.data?.list || []
}

async function onProductChange() {
  currentSchema.value = null
  Object.keys(formFilterValues).forEach((k) => delete formFilterValues[k])
  if (!params.query!.productId) {
    list.value = []
    handleQuery()
    return
  }
  await refreshCurrentProductSchema()
  handleQuery()
}

async function refreshCurrentProductSchema() {
  const productId = params.query!.productId
  if (!productId) return
  const res = await getProduct(productId)
  currentSchema.value = (res.data?.deviceFormSchema as Record<string, unknown>) || null
  const product = res.data
  if (product && !productOptions.value.find((p) => p.productId === product.productId)) {
    productOptions.value = [product, ...productOptions.value]
  }
}

async function loadData() {
  loading.value = true
  try {
    const filters: Record<string, DeviceFormFilter> = {}
    for (const [k, v] of Object.entries(formFilterValues)) {
      if (v.value || (v.operator && v.number !== undefined)) filters[k] = {...v}
    }
    params.query!.formFilters = Object.keys(filters).length ? filters : undefined
    const res = await listDevices(params)
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
  } finally {
    loading.value = false
  }
}

function filterFor(key: string): DeviceFormFilter {
  if (!formFilterValues[key]) formFilterValues[key] = {}
  return formFilterValues[key]
}

function handleQuery() {
  params.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadData()
}

function handleReset() {
  params.query!.deviceCode = ''
  params.query!.deviceName = ''
  params.query!.status = undefined
  Object.keys(formFilterValues).forEach((k) => delete formFilterValues[k])
  handleQuery()
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const formDataValues = reactive<Record<string, unknown>>({})
const form = reactive({
  deviceId: undefined as number | undefined,
  productId: undefined as number | undefined,
  deviceCode: '',
  deviceName: '',
  nodeType: 1,
  gatewayId: undefined as number | undefined,
  longitude: undefined as number | undefined,
  latitude: undefined as number | undefined,
  address: '',
  deviceFormData: formDataValues,
  version: 0
})
const formRules = computed<FormRules>(() => ({
  nodeType: [{required: true, message: '请选择节点类型', trigger: 'change'}],
  deviceCode: [{required: true, message: '请输入设备编码', trigger: 'blur'}],
  gatewayId: [{
    validator: (_rule, value, callback) => {
      if (form.nodeType === 3 && !value) {
        callback(new Error('子设备必须选择所属网关'))
        return
      }
      callback()
    }, trigger: 'blur'
  }],
  ...Object.fromEntries(formFields.value
      .filter(field => field.required && (!form.deviceId || !field.sensitive))
      .map(field => [`deviceFormData.${field.key}`, [{
        required: true,
        message: `请填写${field.label}`,
        trigger: 'blur'
      }]]))
}))

const selectedProductLabel = computed(() => {
  const product = formProduct.value || productOptions.value.find(item => item.productId === params.query!.productId)
  return product ? `${product.productName} (${product.productKey})` : ''
})

/** 与普通输入框同宽，避免 el-input-number 控件导致视觉不齐 */
const gatewayIdText = computed({
  get: () => (form.gatewayId == null ? '' : String(form.gatewayId)),
  set: (v: string) => {
    const n = Number(String(v).trim())
    form.gatewayId = Number.isFinite(n) && n > 0 ? n : undefined
  }
})

function openCreate() {
  const product = productOptions.value.find(item => item.productId === params.query!.productId)
  if (!product || !params.query!.productId) return
  form.deviceId = undefined
  form.productId = params.query!.productId
  form.deviceCode = ''
  form.deviceName = ''
  form.nodeType = product.nodeType || 1
  form.gatewayId = undefined
  form.longitude = undefined
  form.latitude = undefined
  form.address = ''
  form.version = 0
  formProduct.value = product
  formSchema.value = currentSchema.value
  Object.keys(formDataValues).forEach((k) => delete formDataValues[k])
  for (const field of formFields.value) {
    if (field.default !== undefined) formDataValues[field.key] = field.default
  }
  dialogVisible.value = true
}

async function openEdit(row: DeviceVO) {
  const res = await getDevice(row.deviceId)
  const detail = res.data
  if (!detail) return
  const productRes = await getProduct(detail.productId)
  const product = productRes.data
  if (!product) return
  form.deviceId = detail.deviceId
  form.productId = detail.productId
  form.deviceCode = detail.deviceCode
  form.deviceName = detail.deviceName || ''
  form.nodeType = detail.nodeType
  form.gatewayId = detail.gatewayId
  form.longitude = detail.longitude
  form.latitude = detail.latitude
  form.address = detail.address || ''
  form.version = detail.version || 0
  formProduct.value = product
  formSchema.value = (product.deviceFormSchema as Record<string, unknown>) || null
  Object.keys(formDataValues).forEach((k) => delete formDataValues[k])
  for (const [k, v] of Object.entries(detail.deviceFormData || {})) {
    formDataValues[k] = v
  }
  dialogVisible.value = true
}

function openDetail(row: DeviceVO) {
  router.push({name: 'IotDeviceDetail', params: {deviceId: row.deviceId}})
}

async function handleSubmit() {
  await formRef.value?.validate()
  if (!form.productId) return
  const deviceFormData: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(formDataValues)) {
    if (v !== undefined && v !== '') deviceFormData[k] = v
  }
  const payload = {
    productId: form.productId,
    deviceCode: form.deviceCode || undefined,
    deviceName: form.deviceName || undefined,
    nodeType: form.nodeType,
    gatewayId: form.nodeType === 3 ? form.gatewayId : undefined,
    longitude: form.longitude,
    latitude: form.latitude,
    address: form.address || undefined,
    deviceFormData,
    version: form.version
  }
  if (form.deviceId) {
    await updateDevice(form.deviceId, payload)
    ElMessage.success('已更新')
  } else {
    await createDevice(payload)
    ElMessage.success('已创建')
  }
  dialogVisible.value = false
  loadData()
}

async function handleDelete(row: DeviceVO) {
  await ElMessageBox.confirm(`删除设备「${row.deviceCode}」？`, '提示', {type: 'warning'})
  await deleteDevice(row.deviceId)
  ElMessage.success('已删除')
  loadData()
}

onMounted(async () => {
  await searchProducts('')
  if (params.query!.productId) {
    await onProductChange()
    if (route.query.action === 'create') {
      openCreate()
    }
  } else {
    await loadData()
  }
})

onActivated(() => {
  refreshCurrentProductSchema()
})
</script>

<template>
  <div class="flex h-full min-h-0 flex-col bg-white p-3">
    <div class="mb-2 shrink-0 text-[11px] leading-4 text-gray-500">
      下游收到即已写入时序库；重复由下游按 msgId 幂等
    </div>

    <el-form
        :inline="true"
        :model="params.query"
        size="small"
        class="compact-query-form mb-2 shrink-0"
    >
      <el-form-item
          label="关键词"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.keyword"
            clearable
            class="!w-[160px]"
            placeholder="名称 / 编码"
            @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item
          label="消息类型"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.messageType"
            clearable
            placeholder="全部"
            class="!w-[120px]"
        >
          <el-option
              v-for="item in messageTypes"
              :key="item.value"
              :label="item.label"
              :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item
          label="产品"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.productId"
            clearable
            filterable
            placeholder="全部"
            class="!w-[180px]"
        >
          <el-option
              v-for="product in products"
              :key="product.productId"
              :label="`${product.productName} (${product.productKey})`"
              :value="product.productId"
          />
        </el-select>
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
              label="停用"
              :value="0"
          />
          <el-option
              label="启用"
              :value="1"
          />
        </el-select>
      </el-form-item>
      <div class="flex gap-1 !ml-auto">
        <el-button
            v-hasPermi="['iot:rule-route:list']"
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
    </el-form>

    <div class="compact-action-toolbar">
      <el-button
          v-hasPermi="['iot:rule-route:add']"
          type="primary"
          size="small"
          @click="openCreate"
      >
        <el-icon>
          <Plus/>
        </el-icon>
        新建透传路由
      </el-button>
      <span class="ml-auto text-[11px] text-gray-400">共 {{ total }} 条</span>
    </div>

    <div class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="rows"
          border
          stripe
          size="small"
          height="100%"
      >
        <el-table-column
            prop="ruleCode"
            label="编码"
            min-width="140"
            show-overflow-tooltip
        />
        <el-table-column
            prop="ruleName"
            label="名称"
            min-width="160"
            show-overflow-tooltip
        />
        <el-table-column
            label="消息类型"
            width="100"
        >
          <template #default="{ row }">
            {{ messageTypeLabel(row.messageType) }}
          </template>
        </el-table-column>
        <el-table-column
            prop="productCount"
            label="产品数"
            width="80"
            align="center"
        />
        <el-table-column
            label="目标 Topic"
            min-width="180"
            show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ (row.targetTopics || []).join('、') || '—' }}
          </template>
        </el-table-column>
        <el-table-column
            label="状态"
            width="90"
            align="center"
        >
          <template #default="{ row }">
            <el-switch
                v-hasPermi="['iot:rule-route:status']"
                :model-value="row.status === 1"
                :loading="statusChangingId === row.ruleId"
                @change="value => changeStatus(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column
            label="操作"
            width="170"
            fixed="right"
        >
          <template #default="{ row }">
            <el-button
                v-hasPermi="['iot:rule-route:query']"
                link
                type="primary"
                size="small"
                @click="openEdit(row.ruleId, true)"
            >
              查看
            </el-button>
            <el-button
                v-hasPermi="['iot:rule-route:edit']"
                link
                type="primary"
                size="small"
                @click="openEdit(row.ruleId)"
            >
              编辑
            </el-button>
            <el-button
                v-hasPermi="['iot:rule-route:remove']"
                link
                type="danger"
                size="small"
                @click="removeRow(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="compact-pagination">
      <el-pagination
          v-model:current-page="params.pageNum"
          v-model:page-size="params.pageSize"
          small
          background
          :total="total"
          :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
          layout="total, prev, pager, next, sizes"
          @change="loadData"
      />
    </div>

    <el-dialog
        v-model="dialogVisible"
        :title="dialogTitle"
        width="640px"
        :close-on-click-modal="false"
        align-center
        destroy-on-close
        class="compact-edit-dialog"
        @closed="resetForm"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="formRules"
          :disabled="readonly"
          label-width="92px"
          size="small"
          class="compact-edit-form"
      >
        <div class="grid grid-cols-1 gap-x-3 md:grid-cols-3">
          <el-form-item
              label="编码"
              prop="ruleCode"
              :required="!editingId"
          >
            <el-input
                v-model="form.ruleCode"
                :disabled="Boolean(editingId)"
                maxlength="50"
            />
          </el-form-item>
          <el-form-item
              label="名称"
              prop="ruleName"
              required
          >
            <el-input
                v-model="form.ruleName"
                maxlength="120"
            />
          </el-form-item>
          <el-form-item
              label="消息类型"
              prop="messageType"
              required
          >
            <el-select
                v-model="form.messageType"
                class="!w-full"
            >
              <el-option
                  v-for="item in messageTypes"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
              />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item
            label="产品"
            prop="productIds"
            required
        >
          <el-select
              v-model="form.productIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              class="!w-full"
              placeholder="选择透传作用的普通产品"
              @change="clearConflict"
          >
            <el-option
                v-for="product in products"
                :key="product.productId"
                :label="`${product.productName} (${product.productKey})`"
                :value="product.productId"
            />
          </el-select>
        </el-form-item>
        <el-form-item
            label="Kafka 输出"
            prop="kafkaOutputIds"
            required
        >
          <el-select
              v-model="form.kafkaOutputIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              class="!w-full"
              placeholder="只列出用途为透传路由的输出定义"
              @change="clearConflict"
          >
            <el-option
                v-for="item in routeOutputs"
                :key="item.outputId"
                :label="`${item.outputName} · ${item.targetTopic} · ${formatLabel(item.format)}`"
                :value="item.outputId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button
              size="small"
              @click="dialogVisible = false"
          >
            {{ readonly ? '关闭' : '取消' }}
          </el-button>
          <el-button
              v-if="!readonly"
              v-hasPermi="['iot:rule-route:edit', 'iot:rule-route:add']"
              type="primary"
              size="small"
              :loading="saving"
              @click="save"
          >
            保存
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'
import {getErrorMessage, isCancelError} from '@/utils/error'
import {listProducts, type ProductVO} from '@/api/device'
import {type KafkaOutputVO, listKafkaOutputs, type OutputFormat,} from '@/api/ruleKafkaOutput'
import {
  createRuleRoute,
  deleteRuleRoute,
  getRuleRoute,
  listRuleRoutes,
  type RouteMessageType,
  type RuleRouteQuery,
  type RuleRouteSaveRequest,
  type RuleRouteVO,
  updateRuleRoute,
  updateRuleRouteStatus,
} from '@/api/ruleRoute'

type RouteForm = {
  ruleCode: string
  ruleName: string
  messageType: RouteMessageType
  productIds: number[]
  kafkaOutputIds: number[]
  version?: number
}

const messageTypes: Array<{ label: string; value: RouteMessageType }> = [
  {label: '属性', value: 'property'},
  {label: '事件', value: 'event'},
]

const emptyForm = (): RouteForm => ({
  ruleCode: '',
  ruleName: '',
  messageType: 'property',
  productIds: [],
  kafkaOutputIds: [],
})

const params = reactive<PageRequest<RuleRouteQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {},
})
const rows = ref<RuleRouteVO[]>([])
const products = ref<ProductVO[]>([])
const routeOutputs = ref<KafkaOutputVO[]>([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const statusChangingId = ref<number>()
const dialogVisible = ref(false)
const editingId = ref<number>()
const readonly = ref(false)
const conflictMessage = ref('')
const formRef = ref<FormInstance>()
const form = reactive<RouteForm>(emptyForm())

const dialogTitle = computed(() => {
  if (readonly.value) return '查看透传路由'
  return editingId.value ? '编辑透传路由' : '新建透传路由'
})

const formRules: FormRules = {
  ruleCode: [{
    validator: (_rule, value, callback) => editingId.value || String(value || '').trim()
        ? callback() : callback(new Error('规则编码不能为空')),
    trigger: 'blur',
  }],
  ruleName: [{required: true, message: '规则名称不能为空', trigger: 'blur'}],
  messageType: [{required: true, message: '消息类型不能为空', trigger: 'change'}],
  productIds: [
    {type: 'array', required: true, min: 1, message: '至少绑定一个产品', trigger: 'change'},
    {
      validator: (_rule, _value, callback) => conflictMessage.value
          ? callback(new Error(conflictMessage.value)) : callback(),
      trigger: 'change',
    },
  ],
  kafkaOutputIds: [
    {type: 'array', required: true, min: 1, message: '至少绑定一个 Kafka 输出', trigger: 'change'},
    {
      validator: (_rule, _value, callback) => conflictMessage.value
          ? callback(new Error(conflictMessage.value)) : callback(),
      trigger: 'change',
    },
  ],
}

function messageTypeLabel(value: RouteMessageType): string {
  return messageTypes.find(item => item.value === value)?.label || value
}

function formatLabel(value: OutputFormat): string {
  return value === 'MESSAGEPACK' ? 'MessagePack' : value
}

function clearConflict() {
  if (!conflictMessage.value) return
  conflictMessage.value = ''
  formRef.value?.clearValidate(['productIds', 'kafkaOutputIds'])
}

async function loadData() {
  loading.value = true
  try {
    const response = await listRuleRoutes(params)
    rows.value = response.data?.list || []
    total.value = response.data?.total || 0
  } catch (error: unknown) {
    console.error('加载透传路由失败', error)
    ElMessage.error(getErrorMessage(error, '加载透传路由失败'))
  } finally {
    loading.value = false
  }
}

async function loadProducts() {
  try {
    const response = await listProducts({pageNum: 1, pageSize: 1000, query: {productType: 1}})
    products.value = response.data?.list || []
  } catch (error: unknown) {
    console.error('加载产品列表失败', error)
    ElMessage.error(getErrorMessage(error, '加载产品列表失败'))
  }
}

async function loadRouteOutputs() {
  try {
    const response = await listKafkaOutputs({
      pageNum: 1,
      pageSize: 1000,
      query: {purpose: 'ROUTE'},
    })
    routeOutputs.value = response.data?.list || []
  } catch (error: unknown) {
    console.error('加载 Kafka 输出定义失败', error)
    ElMessage.error(getErrorMessage(error, '加载 Kafka 输出定义失败'))
  }
}

function handleQuery() {
  params.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadData()
}

function handleReset() {
  params.query = {}
  handleQuery()
}

function resetForm() {
  editingId.value = undefined
  readonly.value = false
  conflictMessage.value = ''
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

async function openCreate() {
  resetForm()
  dialogVisible.value = true
  await Promise.all([loadProducts(), loadRouteOutputs()])
}

async function openEdit(ruleId: number, viewOnly = false) {
  resetForm()
  editingId.value = ruleId
  readonly.value = viewOnly
  dialogVisible.value = true
  try {
    const [detailResponse] = await Promise.all([
      getRuleRoute(ruleId),
      loadProducts(),
      loadRouteOutputs(),
    ])
    const detail = detailResponse.data
    if (!detail) return
    Object.assign(form, {
      ruleCode: detail.ruleCode,
      ruleName: detail.ruleName,
      messageType: detail.messageType,
      productIds: (detail.products || []).map(item => item.productId),
      kafkaOutputIds: (detail.kafkaOutputs || []).map(item => item.outputId),
      version: detail.version,
    })
  } catch (error: unknown) {
    console.error('加载透传路由详情失败', error)
    ElMessage.error(getErrorMessage(error, '加载透传路由详情失败'))
  }
}

function buildRequest(): RuleRouteSaveRequest {
  return {
    ruleCode: form.ruleCode.trim(),
    ruleName: form.ruleName.trim(),
    messageType: form.messageType,
    productIds: form.productIds,
    kafkaOutputIds: form.kafkaOutputIds,
    version: form.version,
  }
}

async function applyConflict(message: string) {
  conflictMessage.value = message
  await nextTick()
  try {
    await formRef.value?.validateField(['productIds', 'kafkaOutputIds'])
  } catch (error: unknown) {
    console.debug('透传路由冲突已定位到产品与 Kafka 输出', error)
  }
  formRef.value?.scrollToField('productIds')
}

async function save() {
  try {
    await formRef.value?.validate()
  } catch (error: unknown) {
    console.debug('透传路由表单校验未通过', error)
    return
  }
  saving.value = true
  try {
    const payload = buildRequest()
    if (editingId.value) {
      await updateRuleRoute(editingId.value, payload)
    } else {
      await createRuleRoute(payload)
    }
    ElMessage.success('透传路由已保存')
    dialogVisible.value = false
    await loadData()
  } catch (error: unknown) {
    console.error('保存透传路由失败', error)
    const message = getErrorMessage(error, '保存透传路由失败')
    ElMessage.error(message)
    if (/已由规则|透传到/.test(message)) {
      await applyConflict(message)
    }
  } finally {
    saving.value = false
  }
}

async function changeStatus(row: RuleRouteVO, value: string | number | boolean) {
  statusChangingId.value = row.ruleId
  try {
    await updateRuleRouteStatus(row.ruleId, {status: value ? 1 : 0, version: row.version})
    ElMessage.success(value ? '透传路由已启用' : '透传路由已停用')
    await loadData()
  } catch (error: unknown) {
    console.error('透传路由启停失败', error)
    ElMessage.error(getErrorMessage(error, '透传路由启停失败'))
  } finally {
    statusChangingId.value = undefined
  }
}

async function removeRow(row: RuleRouteVO) {
  try {
    await ElMessageBox.confirm(`确定删除透传路由「${row.ruleName}」？`, '删除确认', {type: 'warning'})
    await deleteRuleRoute(row.ruleId)
    ElMessage.success('透传路由已删除')
    await loadData()
  } catch (error: unknown) {
    if (isCancelError(error)) {
      console.debug('用户取消删除透传路由', error)
      return
    }
    console.error('删除透传路由失败', error)
    ElMessage.error(getErrorMessage(error, '删除透传路由失败'))
  }
}

onMounted(async () => {
  await Promise.all([loadProducts(), loadData()])
})
</script>

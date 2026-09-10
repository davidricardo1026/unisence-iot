<template>
  <div class="flex h-full min-h-0 flex-col bg-white p-3">
    <div class="mb-2 shrink-0 text-[11px] leading-4 text-gray-500">
      写入 Kafka 后由下游消费方负责转发与重试
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
            placeholder="编码 / 名称 / Topic"
            @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item
          label="用途"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.purpose"
            clearable
            placeholder="全部"
            class="!w-[140px]"
        >
          <el-option
              v-for="item in purposeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
          />
        </el-select>
      </el-form-item>
      <div class="flex gap-1 !ml-auto">
        <el-button
            v-hasPermi="['iot:rule-kafka-output:list']"
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
          v-hasPermi="['iot:rule-kafka-output:add']"
          type="primary"
          size="small"
          @click="openCreate"
      >
        <el-icon>
          <Plus/>
        </el-icon>
        新增
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
            prop="outputCode"
            label="编码"
            min-width="140"
            show-overflow-tooltip
        />
        <el-table-column
            prop="outputName"
            label="名称"
            min-width="140"
            show-overflow-tooltip
        />
        <el-table-column
            label="用途"
            width="110"
        >
          <template #default="{ row }">
            {{ purposeLabel(row.purpose) }}
          </template>
        </el-table-column>
        <el-table-column
            prop="targetTopic"
            label="Topic"
            min-width="180"
            show-overflow-tooltip
        />
        <el-table-column
            label="格式"
            width="120"
        >
          <template #default="{ row }">
            {{ formatLabel(row.format) }}
          </template>
        </el-table-column>
        <el-table-column
            prop="referenceCount"
            label="引用数"
            width="80"
            align="center"
        />
        <el-table-column
            label="操作"
            width="170"
            fixed="right"
        >
          <template #default="{ row }">
            <el-button
                v-hasPermi="['iot:rule-kafka-output:query']"
                link
                type="primary"
                size="small"
                @click="openEdit(row, true)"
            >
              查看
            </el-button>
            <el-button
                v-hasPermi="['iot:rule-kafka-output:edit']"
                link
                type="primary"
                size="small"
                @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
                v-if="!(row.referenceCount > 0)"
                v-hasPermi="['iot:rule-kafka-output:remove']"
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
        width="560px"
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
          label-width="84px"
          size="small"
          class="compact-edit-form"
      >
        <div class="grid grid-cols-1 gap-x-3 md:grid-cols-2">
          <el-form-item
              label="编码"
              prop="outputCode"
              required
          >
            <el-input
                v-model="form.outputCode"
                :disabled="referenced || Boolean(editingId)"
                maxlength="50"
                placeholder="创建后不可改"
            />
          </el-form-item>
          <el-form-item
              label="名称"
              prop="outputName"
              required
          >
            <el-input
                v-model="form.outputName"
                maxlength="120"
            />
          </el-form-item>
          <el-form-item
              label="用途"
              prop="purpose"
              required
          >
            <el-select
                v-model="form.purpose"
                :disabled="referenced"
                class="!w-full"
            >
              <el-option
                  v-for="item in purposeOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item
              label="格式"
              prop="format"
              required
          >
            <el-select
                v-model="form.format"
                :disabled="referenced"
                class="!w-full"
            >
              <el-option
                  v-for="item in formatOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item
              label="Topic"
              prop="targetTopic"
              required
              class="md:col-span-2"
          >
            <el-input
                v-model="form.targetTopic"
                :disabled="referenced"
                maxlength="249"
                placeholder="同集群静态精确 Topic"
            />
          </el-form-item>
        </div>
        <div
            v-if="referenced"
            class="mb-2 text-[11px] leading-4 text-amber-600"
        >
          该定义已被引用，仅允许修改名称
        </div>
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
              v-hasPermi="['iot:rule-kafka-output:edit', 'iot:rule-kafka-output:add']"
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
import {
  createKafkaOutput,
  deleteKafkaOutput,
  getKafkaOutput,
  type KafkaOutputPurpose,
  type KafkaOutputQuery,
  type KafkaOutputSaveRequest,
  type KafkaOutputVO,
  listKafkaOutputs,
  type OutputFormat,
  updateKafkaOutput,
} from '@/api/ruleKafkaOutput'

type KafkaOutputForm = {
  outputCode: string
  outputName: string
  purpose: KafkaOutputPurpose
  targetTopic: string
  format: OutputFormat
  version?: number
}

const purposeOptions: Array<{ label: string; value: KafkaOutputPurpose }> = [
  {label: '规则输出', value: 'RULE_OUTPUT'},
  {label: '透传路由', value: 'ROUTE'},
]
const formatOptions: Array<{ label: string; value: OutputFormat }> = [
  {label: 'JSON', value: 'JSON'},
  {label: 'MessagePack', value: 'MESSAGEPACK'},
]

const TOPIC_PATTERN = /^[a-zA-Z0-9._-]{1,249}$/

const emptyForm = (): KafkaOutputForm => ({
  outputCode: '',
  outputName: '',
  purpose: 'RULE_OUTPUT',
  targetTopic: '',
  format: 'JSON',
})

const params = reactive<PageRequest<KafkaOutputQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {},
})
const rows = ref<KafkaOutputVO[]>([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number>()
const readonly = ref(false)
const referenced = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<KafkaOutputForm>(emptyForm())

const dialogTitle = computed(() => {
  if (readonly.value) return '查看 Kafka 输出'
  return editingId.value ? '编辑 Kafka 输出' : '新建 Kafka 输出'
})

const formRules: FormRules = {
  outputCode: [{required: true, message: '输出编码不能为空', trigger: 'blur'}],
  outputName: [{required: true, message: '输出名称不能为空', trigger: 'blur'}],
  purpose: [{required: true, message: '用途不能为空', trigger: 'change'}],
  format: [{required: true, message: '格式不能为空', trigger: 'change'}],
  targetTopic: [
    {required: true, message: '目标 Topic 不能为空', trigger: 'blur'},
    {
      pattern: TOPIC_PATTERN,
      message: 'Topic 只允许字母、数字、点、下划线、连字符，最长 249 字符',
      trigger: 'blur',
    },
  ],
}

function purposeLabel(value: KafkaOutputPurpose): string {
  return purposeOptions.find(item => item.value === value)?.label || value
}

function formatLabel(value: OutputFormat): string {
  return formatOptions.find(item => item.value === value)?.label || value
}

async function loadData() {
  loading.value = true
  try {
    const response = await listKafkaOutputs(params)
    rows.value = response.data?.list || []
    total.value = response.data?.total || 0
  } catch (error: unknown) {
    console.error('加载 Kafka 输出定义失败', error)
    ElMessage.error(getErrorMessage(error, '加载 Kafka 输出定义失败'))
  } finally {
    loading.value = false
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
  referenced.value = false
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

async function openEdit(row: KafkaOutputVO, viewOnly = false) {
  resetForm()
  editingId.value = row.outputId
  readonly.value = viewOnly
  dialogVisible.value = true
  try {
    const response = await getKafkaOutput(row.outputId)
    const detail = response.data || row
    referenced.value = (detail.referenceCount || 0) > 0
    Object.assign(form, {
      outputCode: detail.outputCode,
      outputName: detail.outputName,
      purpose: detail.purpose,
      targetTopic: detail.targetTopic,
      format: detail.format,
      version: detail.version,
    })
  } catch (error: unknown) {
    console.error('加载 Kafka 输出定义失败', error)
    ElMessage.error(getErrorMessage(error, '加载 Kafka 输出定义失败'))
  }
}

function buildRequest(): KafkaOutputSaveRequest {
  return {
    outputCode: form.outputCode.trim(),
    outputName: form.outputName.trim(),
    purpose: form.purpose,
    targetTopic: form.targetTopic.trim(),
    format: form.format,
    version: form.version,
  }
}

async function save() {
  try {
    await formRef.value?.validate()
  } catch (error: unknown) {
    console.debug('Kafka 输出表单校验未通过', error)
    return
  }
  saving.value = true
  try {
    const payload = buildRequest()
    if (editingId.value) {
      await updateKafkaOutput(editingId.value, payload)
    } else {
      await createKafkaOutput(payload)
    }
    ElMessage.success('Kafka 输出已保存')
    dialogVisible.value = false
    await loadData()
  } catch (error: unknown) {
    console.error('保存 Kafka 输出失败', error)
    ElMessage.error(getErrorMessage(error, '保存 Kafka 输出失败'))
  } finally {
    saving.value = false
  }
}

async function removeRow(row: KafkaOutputVO) {
  try {
    await ElMessageBox.confirm(`确定删除 Kafka 输出「${row.outputName}」？`, '删除确认', {type: 'warning'})
    await deleteKafkaOutput(row.outputId)
    ElMessage.success('Kafka 输出已删除')
    await loadData()
  } catch (error: unknown) {
    if (isCancelError(error)) {
      console.debug('用户取消删除 Kafka 输出', error)
      return
    }
    console.error('删除 Kafka 输出失败', error)
    ElMessage.error(getErrorMessage(error, '删除 Kafka 输出失败'))
  }
}

onMounted(loadData)
</script>

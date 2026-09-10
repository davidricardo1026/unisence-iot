<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white p-3 overflow-hidden">
    <el-form
        :model="params"
        inline
        size="small"
        class="compact-query-form mb-2 shrink-0"
    >
      <el-form-item
          label="名称"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.productName"
            clearable
            class="!w-[130px]"
        />
      </el-form-item>
      <el-form-item
          label="标识"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.productKey"
            clearable
            class="!w-[130px]"
        />
      </el-form-item>
      <el-form-item
          label="节点"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.nodeType"
            clearable
            placeholder="全部"
            class="!w-[100px]"
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
      <el-form-item label="标签" class="!mb-0">
        <el-select
            v-model="params.query!.tagId"
            clearable
            filterable
            placeholder="全部"
            class="!w-[180px]"
        >
          <el-option
              v-for="tag in tagOptions"
              :key="tag.tagId"
              :label="`${tag.tagKey}: ${tag.tagValue}`"
              :value="tag.tagId"
          >
            <span class="mr-1.5 inline-block h-2.5 w-2.5 rounded-full" :style="{background: tag.color || '#CBD5E1'}"/>
            {{ tag.tagKey }}: {{ tag.tagValue }}
          </el-option>
        </el-select>
      </el-form-item>
      <div class="flex gap-1 !ml-auto">
        <el-button
            v-hasPermi="['iot:product:list']"
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
          v-hasPermi="['iot:product:add']"
          type="primary"
          size="small"
          @click="openCreate"
      >
        <el-icon>
          <Plus/>
        </el-icon>
        新建产品
      </el-button>
      <div class="ml-auto flex items-center gap-2">
        <span class="text-[11px] text-gray-400">共 {{ total }} 条产品</span>
        <el-button
            v-if="viewMode === 'table'"
            v-hasPermi="['iot:product:remove']"
            type="danger"
            size="small"
            :disabled="selectedProductIds.length === 0"
            @click="handleBatchDelete"
        >
          批量删除{{ selectedProductIds.length ? `（${selectedProductIds.length}）` : '' }}
        </el-button>
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button value="card">卡片</el-radio-button>
          <el-radio-button value="table">表格</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div v-if="viewMode === 'table'" class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="list"
          border
          stripe
          size="small"
          height="100%"
          style="width: 100%"
          @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="42" align="center"/>
        <el-table-column label="产品名称" min-width="160">
          <template #default="{row}">
            <button class="flex min-w-0 items-center gap-2 text-left" type="button" @click="openDetail(row)">
              <span class="flex h-6 w-6 shrink-0 items-center justify-center overflow-hidden rounded bg-gray-100">
                <el-icon :size="14"><component :is="getProductIcon(row.icon)"/></el-icon>
              </span>
              <span class="min-w-0 truncate text-[12px] font-medium">{{ row.productName }}</span>
            </button>
          </template>
        </el-table-column>
        <el-table-column prop="productKey" label="产品标识" min-width="140" show-overflow-tooltip>
          <template #default="{row}"><span class="font-mono text-[11px]">{{ row.productKey }}</span></template>
        </el-table-column>
        <el-table-column label="节点" width="80" align="center">
          <template #default="{row}">{{ nodeTypeLabel(row.nodeType) }}</template>
        </el-table-column>
        <el-table-column prop="vendor" label="厂商" min-width="110" show-overflow-tooltip/>
        <el-table-column prop="model" label="型号" min-width="110" show-overflow-tooltip/>
        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{row}">
            <el-button
                v-if="mode === 'standard'"
                v-hasPermi="['iot:product:clone']"
                type="primary"
                link
                size="small"
                @click="handleClone(row)"
            >生成产品
            </el-button>
            <el-button
                v-hasPermi="['iot:product:remove']"
                type="danger"
                link
                size="small"
                @click="handleDelete(row)"
            >删除
            </el-button>
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
          v-for="item in list"
          :key="item.productId"
          class="relative h-[90px] border border-gray-200 rounded p-2 hover:border-blue-400 cursor-pointer"
          @click="openDetail(item)"
      >
        <div class="grid grid-cols-[36px_48px_72px] items-start gap-2">
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded bg-gray-100">
            <el-icon :size="20">
              <component :is="getProductIcon(item.icon)"/>
            </el-icon>
          </div>
          <div class="min-w-0 pt-0.5">
            <div class="truncate text-[13px] font-medium text-gray-800">{{ item.productName }}</div>
            <div class="truncate font-mono text-[11px] text-gray-400">{{ item.productKey }}</div>
          </div>
          <img
              v-if="item.iconUrl"
              :src="item.iconUrl"
              class="h-[72px] w-[72px] shrink-0 rounded bg-gray-100 object-cover"
              alt=""
          >
        </div>
        <div
            class="absolute bottom-1 left-2 flex items-center gap-1"
            @click.stop
        >
          <el-tooltip
              v-if="mode === 'standard'"
              content="生成产品"
              placement="top"
              :enterable="false"
          >
            <el-button
                v-hasPermi="['iot:product:clone']"
                type="primary"
                link
                size="small"
                class="!p-1"
                aria-label="生成产品"
                @click="handleClone(item)"
            >
              <el-icon>
                <CopyDocument/>
              </el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip
              v-if="canEdit(item)"
              content="删除"
              placement="top"
              :enterable="false"
          >
            <el-button v-hasPermi="['iot:product:remove']" type="danger" link size="small" class="!p-1"
                       aria-label="删除" @click="handleDelete(item)">
              <el-icon>
                <Delete/>
              </el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
    </div>

    <el-empty
        v-if="!loading && list.length === 0"
        description="暂无产品"
    />

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
        :title="dialogTitle"
        width="520px"
        :close-on-click-modal="false"
        align-center
        destroy-on-close
        class="compact-edit-dialog compact-edit-dialog-scrollable"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-width="76px"
          label-position="right"
          size="small"
          class="compact-edit-form product-form"
      >
        <el-form-item
            label="产品名称"
            prop="productName"
        >
          <el-input
              v-model="form.productName"
              class="!w-full"
              maxlength="100"
              show-word-limit
              placeholder="请输入产品名称"
          />
        </el-form-item>
        <el-form-item
            label="节点类型"
            prop="nodeType"
        >
          <el-select
              v-model="form.nodeType"
              class="!w-full"
              placeholder="请选择"
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
        <el-form-item label="产品厂商">
          <el-input
              v-model="form.vendor"
              class="!w-full"
              maxlength="100"
              placeholder="可空"
          />
        </el-form-item>
        <el-form-item label="产品型号">
          <el-input
              v-model="form.model"
              class="!w-full"
              maxlength="100"
              placeholder="可空"
          />
        </el-form-item>
        <el-form-item label="产品图标">
          <el-input
              v-model="form.icon"
              class="!w-full"
              maxlength="50"
              placeholder="Element Plus 图标名，可空"
          />
        </el-form-item>
        <el-form-item label="产品描述">
          <el-input
              v-model="form.description"
              class="!w-full"
              type="textarea"
              :rows="2"
              placeholder="可空"
          />
        </el-form-item>
        <el-form-item label="动态表单">
          <el-input
              v-model="schemaText"
              class="!w-full"
              type="textarea"
              :rows="5"
              placeholder="设备动态表单 Schema（JSON，可空）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="flex w-full justify-end gap-2">
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

    <el-dialog
        v-model="cloneDialogVisible"
        title="生成产品"
        width="440px"
        :close-on-click-modal="false"
        class="compact-edit-dialog"
    >
      <el-form ref="cloneFormRef" :model="cloneForm" :rules="cloneRules" label-width="76px" size="small"
               class="compact-edit-form clone-product-form">
        <el-form-item label="产品名称" prop="productName">
          <el-input v-model="cloneForm.productName" maxlength="100" show-word-limit/>
        </el-form-item>
        <el-form-item label="产品编码策略" prop="keyStrategy">
          <el-radio-group v-model="cloneForm.keyStrategy" class="clone-key-strategy">
            <el-radio value="inherit">沿用模板编码（{{ cloneSource?.productKey || '—' }}）</el-radio>
            <el-radio value="random">随机生成新编码</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon
                  title="普通产品可直接建设备；模板仅用于定义能力。沿用编码可复用现有驱动配置。"/>
      </el-form>
      <template #footer>
        <el-button size="small" @click="cloneDialogVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="cloning" @click="submitClone">生成产品</el-button>
      </template>
    </el-dialog>

    <ProductDetailDialog
        v-model="detailVisible"
        :product="detailProduct"
        :properties="tmProperties"
        :events="tmEvents"
        :services="tmServices"
        :editable="!!detailProduct && canEdit(detailProduct)"
        @edit="editFromDetail"
    />
  </div>
</template>

<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  batchDeleteProducts,
  cloneProduct,
  createProduct,
  deleteProduct,
  getProduct,
  listProducts,
  listTags,
  type ProductQuery,
  type ProductVO,
  type TagVO,
  type TmEventVO,
  type TmPropertyVO,
  type TmServiceVO,
  updateProduct
} from '@/api/device'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'
import {getProductIcon} from '@/components/device/product-icons'
import {getErrorMessage, isCancelError} from '@/utils/error'

const props = defineProps<{
  mode: 'standard' | 'product'
}>()

const router = useRouter()
const loading = ref(false)
const list = ref<ProductVO[]>([])
const total = ref(0)
const tagOptions = ref<TagVO[]>([])
const viewMode = ref<'card' | 'table'>('card')
const selectedProductIds = ref<number[]>([])

const params = reactive<PageRequest<ProductQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    productName: '',
    productKey: '',
    productType: props.mode === 'standard' ? 2 : 1,
    nodeType: undefined,
    tagId: undefined
  }
})

function nodeTypeLabel(t: number) {
  return ({1: '直连', 2: '网关', 3: '子设备'} as Record<number, string>)[t] || String(t)
}

function canEdit(_item: ProductVO) {
  return true
}

async function loadData() {
  loading.value = true
  try {
    params.query!.productType = props.mode === 'standard' ? 2 : 1
    const res = await listProducts(params)
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
    selectedProductIds.value = []
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  params.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadData()
}

function handleReset() {
  params.query!.productName = ''
  params.query!.productKey = ''
  params.query!.nodeType = undefined
  params.query!.tagId = undefined
  handleQuery()
}

async function loadTagOptions() {
  const res = await listTags({pageNum: 1, pageSize: 200, query: {}})
  tagOptions.value = res.data?.list || []
}

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()
const cloneFormRef = ref<FormInstance>()
const schemaText = ref('')
const form = reactive({
  productId: undefined as number | undefined,
  productName: '',
  nodeType: 1,
  vendor: '',
  model: '',
  icon: '',
  description: '',
  version: 0
})
const rules: FormRules = {
  productName: [{required: true, message: '必填', trigger: 'blur'}],
  nodeType: [{required: true, message: '必填', trigger: 'change'}]
}
const cloneDialogVisible = ref(false)
const cloning = ref(false)
const cloneSource = ref<ProductVO>()
const cloneForm = reactive({
  productName: '',
  keyStrategy: 'inherit' as 'inherit' | 'random'
})
const cloneRules: FormRules = {
  productName: [{required: true, message: '请输入产品名称', trigger: 'blur'}],
  keyStrategy: [{required: true, message: '请选择产品编码策略', trigger: 'change'}]
}

function openCreate() {
  router.push({name: props.mode === 'standard' ? 'IotStandardProductCreate' : 'IotProductCreate'})
}

async function openEdit(item: ProductVO) {
  dialogTitle.value = '编辑产品'
  form.productId = item.productId
  form.productName = item.productName
  form.nodeType = item.nodeType
  form.vendor = item.vendor || ''
  form.model = item.model || ''
  form.icon = item.icon || ''
  form.description = item.description || ''
  form.version = item.version || 0
  schemaText.value = item.deviceFormSchema ? JSON.stringify(item.deviceFormSchema, null, 2) : ''
  const res = await getProduct(item.productId)
  const detail = res.data
  if (detail?.deviceFormSchema) {
    schemaText.value = JSON.stringify(detail.deviceFormSchema, null, 2)
  }
  form.version = detail?.version || form.version
  dialogVisible.value = true
}

const detailVisible = ref(false)
const detailProduct = ref<ProductVO>()
const tmProperties = ref<TmPropertyVO[]>([])
const tmEvents = ref<TmEventVO[]>([])
const tmServices = ref<TmServiceVO[]>([])

async function openDetail(item: ProductVO) {
  await router.push({
    name: props.mode === 'standard' ? 'IotStandardProductWorkbench' : 'IotProductWorkbench',
    params: {productId: item.productId}
  })
}

async function editFromDetail() {
  if (!detailProduct.value) return
  detailVisible.value = false
  await openEdit(detailProduct.value)
}

function parseSchema(): Record<string, unknown> | undefined {
  const t = schemaText.value.trim()
  if (!t) return undefined
  return JSON.parse(t) as Record<string, unknown>
}

async function handleSubmit() {
  await formRef.value?.validate()
  let schema: Record<string, unknown> | undefined
  try {
    schema = parseSchema()
  } catch (error) {
    console.error('解析设备表单 Schema 失败', error)
    ElMessage.error('表单 Schema JSON 非法')
    return
  }
  const payload = {
    productName: form.productName,
    nodeType: form.nodeType,
    vendor: form.vendor || undefined,
    model: form.model || undefined,
    icon: form.icon || undefined,
    description: form.description || undefined,
    deviceFormSchema: schema,
    productType: props.mode === 'standard' ? 2 : 1,
    version: form.version
  }
  if (form.productId) {
    await updateProduct(form.productId, payload)
    ElMessage.success('已更新')
  } else {
    await createProduct(payload)
    ElMessage.success('已创建')
  }
  dialogVisible.value = false
  loadData()
}

function handleClone(item: ProductVO) {
  cloneSource.value = item
  cloneForm.productName = item.productName
  cloneForm.keyStrategy = 'inherit'
  cloneDialogVisible.value = true
}

async function submitClone() {
  const valid = await cloneFormRef.value?.validate().catch(() => false)
  if (!valid || !cloneSource.value) return
  cloning.value = true
  try {
    const res = await cloneProduct(cloneSource.value.productId, {...cloneForm})
    cloneDialogVisible.value = false
    ElMessage.success('普通产品已生成')
    await router.push({path: '/device/product', query: {highlight: String(res.data?.productId || '')}})
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成产品失败，请稍后重试')
  } finally {
    cloning.value = false
  }
}

async function handleDelete(item: ProductVO) {
  await ElMessageBox.confirm(`删除「${item.productName}」？`, '提示', {type: 'warning'})
  await deleteProduct(item.productId)
  ElMessage.success('已删除')
  loadData()
}

function handleSelectionChange(rows: ProductVO[]) {
  selectedProductIds.value = rows.map(row => row.productId)
}

async function handleBatchDelete() {
  if (selectedProductIds.value.length === 0) return
  try {
    await ElMessageBox.confirm(
        `确认删除已选中的 ${selectedProductIds.value.length} 个产品？此操作不可恢复。`,
        '确认批量删除',
        {type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'}
    )
    await batchDeleteProducts(selectedProductIds.value)
    ElMessage.success('批量删除成功')
    await loadData()
  } catch (error: unknown) {
    if (!isCancelError(error)) {
      ElMessage.error(getErrorMessage(error, '批量删除失败'))
    }
  }
}

function goDevices(item: ProductVO) {
  router.push({path: '/device/device', query: {productId: String(item.productId), action: 'create'}})
}

onMounted(async () => {
  await Promise.all([loadTagOptions(), loadData()])
})

watch(viewMode, () => {
  selectedProductIds.value = []
})

defineExpose({
  refresh: loadData
})
</script>

<style scoped>
.clone-key-strategy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
}


.clone-product-form {
  --ui-form-label-width: 92px;
}

.clone-product-form :deep(.el-form-item__label) {
  width: 92px !important;
  white-space: nowrap;
}

.clone-key-strategy :deep(.el-radio) {
  margin-right: 0;
}

.product-form :deep(.el-textarea__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
</style>

<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white p-3">
    <!-- 搜索 -->
    <el-form
        :model="params"
        inline
        size="small"
        class="compact-query-form mb-2 shrink-0"
    >
      <el-form-item
          label="参数名称"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.configName"
            placeholder="请输入"
            clearable
            class="!w-[130px]"
        />
      </el-form-item>
      <el-form-item
          label="参数键名"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.configKey"
            placeholder="请输入"
            clearable
            class="!w-[130px]"
        />
      </el-form-item>
      <el-form-item
          label="类型"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.configType"
            placeholder="全部"
            clearable
            class="!w-[100px]"
        >
          <el-option
              label="系统参数"
              :value="0"
          />
          <el-option
              label="自定义参数"
              :value="1"
          />
        </el-select>
      </el-form-item>
      <div class="flex gap-1 !ml-auto">
        <el-button
            v-hasPermi="['sys:config:list']"
            type="primary"
            size="small"
            @click="handleQuery"
        >
          <el-icon>
            <Search/>
          </el-icon>
          搜索
        </el-button>
        <el-button
            v-hasPermi="['sys:config:list']"
            size="small"
            @click="handleReset"
        >
          <el-icon>
            <Refresh/>
          </el-icon>
          重置
        </el-button>
      </div>
    </el-form>

    <div class="compact-action-toolbar">
      <div class="flex gap-2">
        <el-button
            v-hasPermi="['sys:config:add']"
            type="primary"
            size="small"
            @click="openDialog()"
        >
          <el-icon>
            <Plus/>
          </el-icon>
          新增
        </el-button>
        <el-button
            v-hasPermi="['sys:config:clearCache']"
            size="small"
            @click="handleClearCache"
        >
          <el-icon>
            <Refresh/>
          </el-icon>
          刷新缓存
        </el-button>
      </div>
      <span class="text-[11px] text-gray-400">共 {{ total }} 条参数</span>
    </div>

    <!-- 表格 -->
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
            prop="configName"
            label="参数名称"
            min-width="120"
        />
        <el-table-column
            prop="configKey"
            label="参数键名"
            min-width="140"
        />
        <el-table-column
            prop="configValue"
            label="参数键值"
            min-width="180"
        >
          <template #default="{ row }">
            <span class="text-xs font-mono">{{ row.configValue }}</span>
          </template>
        </el-table-column>
        <el-table-column
            prop="configType"
            label="类型"
            width="90"
            align="center"
        >
          <template #default="{ row }">
            <el-tag
                :type="row.configType === 0 ? '' : 'info'"
                size="small"
            >
              {{ row.configType === 0 ? '系统' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
            label="操作"
            width="90"
            align="center"
            fixed="right"
        >
          <template #default="{ row }">
            <el-tooltip
                content="编辑"
                placement="top"
                :enterable="false"
            >
              <el-button
                  v-hasPermi="['sys:config:edit']"
                  type="primary"
                  link
                  size="small"
                  @click="openDialog(row)"
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
                  v-hasPermi="['sys:config:delete']"
                  type="danger"
                  link
                  size="small"
                  @click="handleDelete(row)"
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

    <!-- 分页 -->
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

    <!-- 弹窗 -->
    <el-dialog
        v-model="dialogVisible"
        :title="dialogTitle"
        width="480px"
        :close-on-click-modal="false"
        class="compact-edit-dialog"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-width="76px"
          size="small"
          class="compact-edit-form"
      >
        <el-form-item
            label="参数名称"
            prop="configName"
        >
          <el-input
              v-model="form.configName"
              placeholder="请输入参数名称（如：用户默认密码）"
          />
        </el-form-item>
        <el-form-item
            label="参数键名"
            prop="configKey"
        >
          <el-input
              v-model="form.configKey"
              placeholder="请输入参数键名（如：sys.user.defPwd）"
              :disabled="!!form.configId"
          />
        </el-form-item>
        <el-form-item
            label="参数键值"
            prop="configValue"
        >
          <el-input
              v-model="form.configValue"
              type="textarea"
              :rows="3"
              placeholder="请输入参数键值"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select
              v-model="form.configType"
              class="!w-full"
          >
            <el-option
                label="系统参数"
                :value="0"
            />
            <el-option
                label="自定义参数"
                :value="1"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
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
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  clearCacheConfig,
  type ConfigQuery,
  type ConfigVO,
  createConfig,
  deleteConfig,
  listConfigs,
  updateConfig
} from '@/api/system'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'

const loading = ref(false)
const list = ref<ConfigVO[]>([])
const total = ref(0)

const params = reactive<PageRequest<ConfigQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    configName: '',
    configKey: '',
    configType: undefined
  }
})

async function loadData() {
  loading.value = true
  try {
    const res = await listConfigs(params)
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
  } catch (error) {
    console.error('加载系统参数失败', error)
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  params.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadData()
}

function handleReset() {
  params.query!.configName = ''
  params.query!.configKey = ''
  params.query!.configType = undefined
  handleQuery()
}

// ==================== CRUD ====================
const dialogVisible = ref(false)
const dialogTitle = ref('新增参数配置')
const formRef = ref<FormInstance>()

const form = reactive({
  configId: undefined as number | undefined,
  configName: '',
  configKey: '',
  configValue: '',
  configType: 1,
  version: 0
})

const rules: FormRules = {
  configName: [{required: true, message: '参数名称不能为空', trigger: 'blur'}],
  configKey: [
    {required: true, message: '参数键名不能为空', trigger: 'blur'},
    {pattern: /^[a-zA-Z0-9_.]+$/, message: '只能包含字母、数字、下划线及点号', trigger: 'blur'}
  ],
  configValue: [{required: true, message: '参数键值不能为空', trigger: 'blur'}]
}

function openDialog(row?: ConfigVO) {
  formRef.value?.resetFields()
  if (row) {
    dialogTitle.value = '编辑参数配置'
    form.configId = row.configId
    form.configName = row.configName
    form.configKey = row.configKey
    form.configValue = row.configValue
    form.configType = row.configType
    form.version = row.version
  } else {
    dialogTitle.value = '新增参数配置'
    form.configId = undefined
    form.configName = ''
    form.configKey = ''
    form.configValue = ''
    form.configType = 1
    form.version = 0
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  try {
    if (form.configId !== undefined) {
      await updateConfig(form.configId, {
        configName: form.configName,
        configValue: form.configValue,
        configType: form.configType,
        version: form.version
      })
      ElMessage.success('参数配置更新成功')
    } else {
      await createConfig({
        configName: form.configName,
        configKey: form.configKey,
        configValue: form.configValue,
        configType: form.configType
      })
      ElMessage.success('参数配置创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err))
  }
}

async function handleDelete(row: ConfigVO) {
  try {
    await ElMessageBox.confirm(
        `确认删除参数配置"${row.configName}"？`, '确认删除',
        {type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'}
    )
    await deleteConfig(row.configId)
    ElMessage.success('删除成功')
    loadData()
  } catch (err: unknown) {
    if (!isCancelError(err)) {
      ElMessage.error(getErrorMessage(err))
    }
  }
}

async function handleClearCache() {
  try {
    await clearCacheConfig()
    ElMessage.success('参数缓存已成功刷新')
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err, '刷新失败'))
  }
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white">
    <div class="flex flex-1 min-h-0">
      <!-- ==================== 左：字典类型 ==================== -->
      <div class="w-[420px] border-r border-gray-200 flex flex-col min-h-0">
        <!-- 搜索 -->
        <div class="px-4 py-3 bg-gray-50/50 border-b border-gray-100">
          <el-form
              :model="typeParams"
              inline
              size="small"
              class="compact-query-form"
          >
            <el-form-item
                label=""
                class="!mb-0"
            >
              <el-input
                  v-model="typeParams.query!.dictName"
                  placeholder="字典名称"
                  clearable
                  class="!w-[120px]"
              />
            </el-form-item>
            <el-form-item
                label=""
                class="!mb-0"
            >
              <el-input
                  v-model="typeParams.query!.dictType"
                  placeholder="字典类型"
                  clearable
                  class="!w-[120px]"
              />
            </el-form-item>
            <div class="flex gap-1 ml-auto">
              <el-button
                  v-hasPermi="['sys:dict:list']"
                  type="primary"
                  size="small"
                  @click="handleTypeQuery"
              >
                <el-icon>
                  <Search/>
                </el-icon>
              </el-button>
              <el-button
                  v-hasPermi="['sys:dict:list']"
                  size="small"
                  @click="handleTypeReset"
              >
                <el-icon>
                  <Refresh/>
                </el-icon>
              </el-button>
              <el-button
                  v-hasPermi="['sys:dict:add']"
                  type="success"
                  size="small"
                  @click="openTypeDialog()"
              >
                <el-icon>
                  <Plus/>
                </el-icon>
                新增
              </el-button>
            </div>
          </el-form>
        </div>

        <!-- 列表 -->
        <div class="compact-table-region mx-4 my-2">
          <el-table
              :data="typeList"
              border
              stripe
              size="small"
              highlight-current-row
              height="100%"
              @current-change="handleTypeSelect"
          >
            <el-table-column
                prop="dictName"
                label="字典名称"
                min-width="90"
            />
            <el-table-column
                prop="dictType"
                label="字典类型"
                min-width="100"
            />
            <el-table-column
                prop="status"
                label="状态"
                width="55"
                align="center"
            >
              <template #default="{ row }">
                <el-switch
                    :model-value="row.status === 1"
                    size="small"
                    @change="handleTypeStatusChange(row)"
                />
              </template>
            </el-table-column>
            <el-table-column
                label="操作"
                width="110"
                align="center"
                fixed="right"
            >
              <template #default="{ row }">
                <el-button
                    v-hasPermi="['sys:dict:edit']"
                    type="primary"
                    link
                    size="small"
                    @click.stop="openTypeDialog(row)"
                >
                  <el-icon>
                    <Edit/>
                  </el-icon>
                </el-button>
                <el-button
                    v-hasPermi="['sys:dict:delete']"
                    type="danger"
                    link
                    size="small"
                    @click.stop="handleDeleteType(row)"
                >
                  <el-icon>
                    <Delete/>
                  </el-icon>
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 分页 -->
        <div class="compact-pagination px-4 pb-2">
          <el-pagination
              v-model:current-page="typeParams.pageNum"
              v-model:page-size="typeParams.pageSize"
              :total="typeTotal"
              :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
              layout="total, prev, pager, next, sizes"
              size="small"
              @size-change="loadTypes"
              @current-change="loadTypes"
          />
        </div>
      </div>

      <!-- ==================== 右：字典数据 ==================== -->
      <div class="flex-1 flex flex-col min-h-0">
        <div class="px-4 py-3 bg-gray-50/50 border-b border-gray-100 flex items-center gap-2">
          <span class="text-xs font-bold text-gray-700 whitespace-nowrap">
            字典数据: {{ selectedType?.dictName || '-' }}
          </span>
          <span
              v-if="selectedType"
              class="text-xs text-gray-400"
          >{{ selectedType.dictType }}</span>
          <div
              v-if="selectedType"
              class="ml-auto flex gap-1"
          >
            <el-input
                v-model="dataParams.query!.dictLabel"
                placeholder="字典标签"
                clearable
                size="small"
                class="!w-[120px]"
                @keyup.enter="loadDictData"
            />
            <el-button
                v-hasPermi="['sys:dict:list']"
                type="primary"
                size="small"
                @click="handleDataQuery"
            >
              <el-icon>
                <Search/>
              </el-icon>
            </el-button>
            <el-button
                v-hasPermi="['sys:dict:add']"
                type="success"
                size="small"
                @click="openDataDialog()"
            >
              <el-icon>
                <Plus/>
              </el-icon>
              新增
            </el-button>
          </div>
        </div>

        <div class="compact-table-region mx-4 my-2">
          <template v-if="selectedType">
            <el-table
                :data="dataList"
                border
                stripe
                size="small"
                height="100%"
            >
              <el-table-column
                  prop="dictLabel"
                  label="字典标签"
                  min-width="100"
              />
              <el-table-column
                  prop="dictValue"
                  label="字典键值"
                  min-width="100"
              />
              <el-table-column
                  prop="sortOrder"
                  label="排序"
                  width="60"
                  align="center"
              />
              <el-table-column
                  prop="status"
                  label="状态"
                  width="60"
                  align="center"
              >
                <template #default="{ row }">
                  <el-switch
                      :model-value="row.status === 1"
                      size="small"
                      @change="handleDataStatusChange(row)"
                  />
                </template>
              </el-table-column>
              <el-table-column
                  label="操作"
                  width="110"
                  align="center"
                  fixed="right"
              >
                <template #default="{ row }">
                  <el-button
                      v-hasPermi="['sys:dict:edit']"
                      type="primary"
                      link
                      size="small"
                      @click="openDataDialog(row)"
                  >
                    <el-icon>
                      <Edit/>
                    </el-icon>
                  </el-button>
                  <el-button
                      v-hasPermi="['sys:dict:delete']"
                      type="danger"
                      link
                      size="small"
                      @click="handleDeleteData(row)"
                  >
                    <el-icon>
                      <Delete/>
                    </el-icon>
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <div
              v-else
              class="h-full flex items-center justify-center text-gray-400 text-sm"
          >
            请在左侧选择一个字典类型
          </div>
        </div>

        <div class="compact-pagination px-4 pb-2">
          <el-pagination
              v-model:current-page="dataParams.pageNum"
              v-model:page-size="dataParams.pageSize"
              :total="dataTotal"
              :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
              layout="total, prev, pager, next, sizes"
              size="small"
              @size-change="loadDictData"
              @current-change="loadDictData"
          />
        </div>
      </div>
    </div>

    <!-- ==================== 字典类型弹窗 ==================== -->
    <el-dialog
        v-model="typeDialogVisible"
        :title="typeDialogTitle"
        width="460px"
        :close-on-click-modal="false"
        class="compact-edit-dialog"
    >
      <el-form
          ref="typeFormRef"
          :model="typeForm"
          :rules="typeRules"
          label-width="76px"
          size="small"
          class="compact-edit-form"
      >
        <el-form-item
            label="字典名称"
            prop="dictName"
        >
          <el-input
              v-model="typeForm.dictName"
              placeholder="请输入字典名称（如：用户性别）"
          />
        </el-form-item>
        <el-form-item
            label="字典类型"
            prop="dictType"
        >
          <el-input
              v-model="typeForm.dictType"
              placeholder="请输入字典类型编码（如：sys_user_sex）"
              :disabled="!!typeForm.dictTypeId"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="typeForm.status">
            <el-radio :value="1">
              正常
            </el-radio>
            <el-radio :value="0">
              停用
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
            size="small"
            @click="typeDialogVisible = false"
        >
          取消
        </el-button>
        <el-button
            size="small"
            type="primary"
            @click="handleTypeSubmit"
        >
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 字典数据弹窗 ==================== -->
    <el-dialog
        v-model="dataDialogVisible"
        :title="dataDialogTitle"
        width="460px"
        :close-on-click-modal="false"
        class="compact-edit-dialog"
    >
      <el-form
          ref="dataFormRef"
          :model="dataForm"
          :rules="dataRules"
          label-width="76px"
          size="small"
          class="compact-edit-form"
      >
        <el-form-item label="所属字典">
          <el-input
              :model-value="selectedType?.dictName"
              disabled
          />
        </el-form-item>
        <el-form-item
            label="字典标签"
            prop="dictLabel"
        >
          <el-input
              v-model="dataForm.dictLabel"
              placeholder="请输入字典标签（如：男）"
          />
        </el-form-item>
        <el-form-item
            label="字典键值"
            prop="dictValue"
        >
          <el-input
              v-model="dataForm.dictValue"
              placeholder="请输入字典键值（如：0）"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
              v-model="dataForm.sortOrder"
              :min="0"
              :max="9999"
              placeholder="排序号"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="dataForm.status">
            <el-radio :value="1">
              正常
            </el-radio>
            <el-radio :value="0">
              停用
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
            size="small"
            @click="dataDialogVisible = false"
        >
          取消
        </el-button>
        <el-button
            size="small"
            type="primary"
            @click="handleDataSubmit"
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
  createDictData,
  createDictType,
  deleteDictData,
  deleteDictType,
  type DictDataQuery,
  type DictDataVO,
  type DictTypeQuery,
  type DictTypeVO,
  listDictTypes,
  pageDictData,
  updateDictData,
  updateDictType
} from '@/api/system'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'

// ==================== 字典类型 - 查询 ====================
const typeParams = reactive<PageRequest<DictTypeQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    dictName: '',
    dictType: '',
    status: undefined
  }
})
const typeList = ref<DictTypeVO[]>([])
const typeTotal = ref(0)
const selectedType = ref<DictTypeVO | null>(null)

async function loadTypes() {
  try {
    const res = await listDictTypes(typeParams)
    typeList.value = res.data?.list || []
    typeTotal.value = res.data?.total || 0
  } catch (error) {
    console.error('加载字典类型失败', error)
  }
}

function handleTypeQuery() {
  typeParams.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadTypes()
}

function handleTypeReset() {
  typeParams.query!.dictName = ''
  typeParams.query!.dictType = ''
  handleTypeQuery()
}

function handleTypeSelect(row: DictTypeVO | null) {
  selectedType.value = row
  dataParams.pageNum = PAGE_DEFAULT.PAGE_NUM
  if (dataParams.query) {
    dataParams.query.dictLabel = ''
  }
  if (row) {
    if (dataParams.query) {
      dataParams.query.dictType = row.dictType
    }
    loadDictData()
  }
}

// ==================== 字典类型 - CRUD ====================
const typeDialogVisible = ref(false)
const typeDialogTitle = ref('新增字典类型')
const typeFormRef = ref<FormInstance>()

const typeForm = reactive({
  dictTypeId: undefined as number | undefined,
  dictName: '',
  dictType: '',
  status: 1,
  version: 0
})

const typeRules: FormRules = {
  dictName: [{required: true, message: '字典名称不能为空', trigger: 'blur'}],
  dictType: [
    {required: true, message: '字典类型不能为空', trigger: 'blur'},
    {pattern: /^[a-z0-9_]+$/, message: '只能包含小写字母、数字及下划线', trigger: 'blur'}
  ]
}

function openTypeDialog(row?: DictTypeVO) {
  typeFormRef.value?.resetFields()
  if (row) {
    typeDialogTitle.value = '编辑字典类型'
    typeForm.dictTypeId = row.dictTypeId
    typeForm.dictName = row.dictName
    typeForm.dictType = row.dictType
    typeForm.status = row.status
    typeForm.version = row.version
  } else {
    typeDialogTitle.value = '新增字典类型'
    typeForm.dictTypeId = undefined
    typeForm.dictName = ''
    typeForm.dictType = ''
    typeForm.status = 1
    typeForm.version = 0
  }
  typeDialogVisible.value = true
}

async function handleTypeSubmit() {
  if (!typeFormRef.value) return
  const valid = await typeFormRef.value.validate().catch(() => false)
  if (!valid) return
  try {
    if (typeForm.dictTypeId !== undefined) {
      await updateDictType(typeForm.dictTypeId, {
        dictName: typeForm.dictName,
        dictType: typeForm.dictType,
        status: typeForm.status,
        version: typeForm.version
      })
      ElMessage.success('字典类型更新成功')
    } else {
      await createDictType({
        dictName: typeForm.dictName,
        dictType: typeForm.dictType,
        status: typeForm.status
      })
      ElMessage.success('字典类型创建成功')
    }
    typeDialogVisible.value = false
    loadTypes()
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err))
  }
}

async function handleDeleteType(row: DictTypeVO) {
  try {
    await ElMessageBox.confirm(
        `删除字典类型"${row.dictName}"后，该类型下的所有字典数据也将被删除，确认删除？`,
        '警告',
        {type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'}
    )
    await deleteDictType(row.dictTypeId)
    ElMessage.success('删除成功')
    if (selectedType.value?.dictTypeId === row.dictTypeId) {
      selectedType.value = null
    }
    loadTypes()
  } catch (err: unknown) {
    if (!isCancelError(err)) {
      ElMessage.error(getErrorMessage(err))
    }
  }
}

async function handleTypeStatusChange(row: DictTypeVO) {
  try {
    await updateDictType(row.dictTypeId, {
      dictName: row.dictName,
      dictType: row.dictType,
      status: row.status === 1 ? 0 : 1,
      version: row.version
    })
    ElMessage.success(`字典类型状态已切换为: ${row.status === 1 ? '停用' : '正常'}`)
    loadTypes() // 🚩 遵从 UI 规范：操作成功后刷新数据
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err, '状态切换失败'))
  }
}

// ==================== 字典数据 - 查询 ====================
const dataParams = reactive<PageRequest<DictDataQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    dictType: '',
    dictLabel: ''
  }
})
const dataList = ref<DictDataVO[]>([])
const dataTotal = ref(0)

async function loadDictData() {
  if (!dataParams.query?.dictType) return
  try {
    const res = await pageDictData(dataParams)
    dataList.value = res.data?.list || []
    dataTotal.value = res.data?.total || 0
  } catch (error) {
    console.error('加载字典数据失败', error)
  }
}

function handleDataQuery() {
  dataParams.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadDictData()
}

// ==================== 字典数据 - CRUD ====================
const dataDialogVisible = ref(false)
const dataDialogTitle = ref('新增字典数据')
const dataFormRef = ref<FormInstance>()

const dataForm = reactive({
  dictDataId: undefined as number | undefined,
  dictLabel: '',
  dictValue: '',
  sortOrder: 0,
  status: 1,
  version: 0
})

const dataRules: FormRules = {
  dictLabel: [{required: true, message: '字典标签不能为空', trigger: 'blur'}],
  dictValue: [{required: true, message: '字典键值不能为空', trigger: 'blur'}]
}

function openDataDialog(row?: DictDataVO) {
  dataFormRef.value?.resetFields()
  if (row) {
    dataDialogTitle.value = '编辑字典数据'
    dataForm.dictDataId = row.dictDataId
    dataForm.dictLabel = row.dictLabel
    dataForm.dictValue = row.dictValue
    dataForm.sortOrder = row.sortOrder
    dataForm.status = row.status
    dataForm.version = row.version
  } else {
    dataDialogTitle.value = '新增字典数据'
    dataForm.dictDataId = undefined
    dataForm.dictLabel = ''
    dataForm.dictValue = ''
    dataForm.sortOrder = 0
    dataForm.status = 1
    dataForm.version = 0
  }
  dataDialogVisible.value = true
}

async function handleDataSubmit() {
  if (!dataFormRef.value) return
  const valid = await dataFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (!selectedType.value) return
  try {
    if (dataForm.dictDataId !== undefined) {
      await updateDictData(dataForm.dictDataId, {
        dictLabel: dataForm.dictLabel,
        dictValue: dataForm.dictValue,
        sortOrder: dataForm.sortOrder,
        status: dataForm.status,
        version: dataForm.version
      })
      ElMessage.success('字典数据更新成功')
    } else {
      await createDictData({
        dictType: selectedType.value.dictType,
        dictLabel: dataForm.dictLabel,
        dictValue: dataForm.dictValue,
        sortOrder: dataForm.sortOrder,
        status: dataForm.status
      })
      ElMessage.success('字典数据创建成功')
    }
    dataDialogVisible.value = false
    loadDictData()
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err))
  }
}

async function handleDeleteData(row: DictDataVO) {
  try {
    await ElMessageBox.confirm(
        `确认删除字典数据"${row.dictLabel}"？`, '确认删除',
        {type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'}
    )
    await deleteDictData(row.dictDataId)
    ElMessage.success('删除成功')
    loadDictData()
  } catch (err: unknown) {
    if (!isCancelError(err)) {
      ElMessage.error(getErrorMessage(err))
    }
  }
}

async function handleDataStatusChange(row: DictDataVO) {
  try {
    await updateDictData(row.dictDataId, {
      dictLabel: row.dictLabel,
      dictValue: row.dictValue,
      sortOrder: row.sortOrder,
      status: row.status === 1 ? 0 : 1,
      version: row.version
    })
    ElMessage.success(`字典数据状态已切换为: ${row.status === 1 ? '停用' : '正常'}`)
    loadDictData() // 🚩 遵从 UI 规范：操作成功后刷新数据
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err, '状态切换失败'))
  }
}

onMounted(() => {
  loadTypes()
})
</script>

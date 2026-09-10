<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {createDept, deleteDept, type DeptTreeVO, getDeptTree, updateDept} from '@/api/system'

// ==========================================
// 1. 数据定义与初始化
// ==========================================
const loading = ref(false)
const tableData = ref<DeptTreeVO[]>([])

// 搜索条件
const queryParams = reactive({
  deptName: '',
  status: undefined as number | undefined
})

// ==========================================
// 2. 表单与弹窗控制
// ==========================================
const dialogVisible = ref(false)
const dialogTitle = ref('新增部门')
const formRef = ref<FormInstance>()

const form = reactive({
  deptId: undefined as number | undefined,
  parentId: 0 as number,
  deptName: '',
  sortOrder: 1,
  leader: '',
  phone: '',
  status: 1,
  version: 0
})

const rules = reactive<FormRules>({
  deptName: [
    {required: true, message: '部门名称不能为空', trigger: 'blur'},
    {min: 2, max: 50, message: '部门名称长度在 2 到 50 个字符之间', trigger: 'blur'}
  ],
  parentId: [
    {required: true, message: '上级部门不能为空', trigger: 'change'}
  ],
  sortOrder: [
    {required: true, message: '排序序号不能为空', trigger: 'blur'}
  ],
  phone: [
    {pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号码', trigger: 'blur'}
  ]
})

// 部门下拉树配置：需要在最顶层追加一个 “无（顶级部门）” 虚拟节点
const deptOptions = ref<DeptTreeVO[]>([])

const treeSelectProps = {
  children: 'children',
  label: 'deptName',
  value: 'deptId'
}

// ==========================================
// 3. 业务逻辑方法
// ==========================================

// 加载部门树
async function loadData() {
  loading.value = true
  try {
    const res = await getDeptTree(queryParams)
    if (res && res.data) {
      tableData.value = res.data

      // 构建下拉部门树配置项，加上默认的顶级虚拟节点
      deptOptions.value = [
        {
          deptId: 0,
          deptName: '无（顶级部门）',
          children: res.data
        }
      ]
    }
  } catch (err) {
    console.error('加载部门列表失败', err)
  } finally {
    loading.value = false
  }
}

// 查询与重置
function handleQuery() {
  loadData()
}

function handleReset() {
  queryParams.deptName = ''
  queryParams.status = undefined
  loadData()
}

// 打开新增（顶级/子部门）或编辑弹窗
function openDialog(row?: DeptTreeVO, isAddChild = false) {
  dialogVisible.value = true
  formRef.value?.resetFields()

  if (row) {
    if (isAddChild) {
      // 新增子部门
      dialogTitle.value = '新增子部门'
      form.deptId = undefined
      form.parentId = row.deptId
      form.deptName = ''
      form.sortOrder = 10
      form.leader = ''
      form.phone = ''
      form.status = 1
      form.version = 0
    } else {
      // 编辑当前部门
      dialogTitle.value = '编辑部门'
      form.deptId = row.deptId
      form.parentId = row.parentId
      form.deptName = row.deptName
      form.sortOrder = row.sortOrder
      form.leader = row.leader || ''
      form.phone = row.phone || ''
      form.status = row.status
      form.version = row.version
    }
  } else {
    // 新增顶级部门
    dialogTitle.value = '新增部门'
    form.deptId = undefined
    form.parentId = 0
    form.deptName = ''
    form.sortOrder = 1
    form.leader = ''
    form.phone = ''
    form.status = 1
    form.version = 0
  }
}

// 提交新增/编辑
async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // 避免自关联：上级部门不能选择当前部门自己
  if (form.deptId !== undefined && form.parentId === form.deptId) {
    ElMessage.error('上级部门不能选择当前部门自身！')
    return
  }

  try {
    if (form.deptId !== undefined) {
      // 编辑
      await updateDept(form.deptId, {
        parentId: form.parentId,
        deptName: form.deptName,
        sortOrder: form.sortOrder,
        leader: form.leader || undefined,
        phone: form.phone || undefined,
        status: form.status,
        version: form.version
      })
      ElMessage.success('部门更新成功')
    } else {
      // 新增
      await createDept({
        parentId: form.parentId,
        deptName: form.deptName,
        sortOrder: form.sortOrder,
        leader: form.leader || undefined,
        phone: form.phone || undefined,
        status: form.status
      })
      ElMessage.success('部门创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err))
  }
}

// 删除部门
function handleDelete(row: DeptTreeVO) {
  // 安全校验：如果有子部门，前端予以友好确认拦截提示（后端也会有校验）
  if (row.children && row.children.length > 0) {
    ElMessageBox.alert(`部门「${row.deptName}」下还存在子部门，无法直接删除！请先移除所有子级部门。`, '提示', {
      type: 'warning'
    })
    return
  }

  ElMessageBox.confirm(`确认删除部门「${row.deptName}」吗？`, '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteDept(row.deptId)
      ElMessage.success('部门已成功删除')
      loadData()
    } catch (err: unknown) {
      ElMessage.error(getErrorMessage(err, '删除失败'))
    }
  }).catch(() => {
  })
}

// 状态快速修改状态
async function handleStatusChange(row: DeptTreeVO) {
  try {
    await updateDept(row.deptId, {
      parentId: row.parentId,
      deptName: row.deptName,
      sortOrder: row.sortOrder,
      leader: row.leader || undefined,
      phone: row.phone || undefined,
      status: row.status,
      version: row.version
    })
    ElMessage.success(`部门状态已切换为: ${row.status === 1 ? '正常' : '停用'}`)
    loadData() // 🚩 遵从 UI 规范：操作成功后刷新数据
  } catch (err: unknown) {
    row.status = row.status === 1 ? 0 : 1 // 状态回滚
    ElMessage.error(getErrorMessage(err, '状态切换失败'))
  }
}

// 挂载加载
onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="flex-1 flex flex-col min-h-0 overflow-hidden bg-white p-3">
    <!-- 1. 紧凑搜索条件 -->
    <el-form
        :inline="true"
        :model="queryParams"
        class="compact-query-form mb-2 shrink-0"
        @submit.prevent="handleQuery"
    >
      <el-form-item label="部门名称">
        <el-input
            v-model="queryParams.deptName"
            placeholder="请输入部门名称"
            size="small"
            clearable
            class="!w-44"
        />
      </el-form-item>
      <el-form-item label="部门状态">
        <el-select
            v-model="queryParams.status"
            placeholder="选择状态"
            size="small"
            clearable
            class="!w-28"
        >
          <el-option
              label="正常"
              :value="1"
          />
          <el-option
              label="停用"
              :value="0"
          />
        </el-select>
      </el-form-item>
      <el-form-item class="!ml-auto">
        <el-button
            v-hasPermi="['sys:dept:list']"
            type="primary"
            size="small"
            native-type="submit"
        >
          <el-icon class="mr-1">
            <Search/>
          </el-icon>
          查询
        </el-button>
        <el-button
            v-hasPermi="['sys:dept:list']"
            size="small"
            @click="handleReset"
        >
          <el-icon class="mr-1">
            <Refresh/>
          </el-icon>
          重置
        </el-button>
      </el-form-item>
    </el-form>

    <!-- 2. 操作组 -->
    <div class="compact-action-toolbar">
      <el-button
          v-hasPermi="['sys:dept:add']"
          type="primary"
          size="small"
          @click="openDialog()"
      >
        <el-icon class="mr-1">
          <Plus/>
        </el-icon>
        新增部门
      </el-button>
    </div>

    <!-- 3. 部门组织表格 (支持默认树形展开) -->
    <div class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="tableData"
          row-key="deptId"
          border
          size="small"
          default-expand-all
          :tree-props="{ children: 'children' }"
          stripe
          height="100%"
      >
        <el-table-column
            prop="deptName"
            label="部门名称"
            min-width="180"
        >
          <template #default="{ row }">
            <span class="font-medium text-gray-700">{{ row.deptName }}</span>
          </template>
        </el-table-column>
        <el-table-column
            prop="sortOrder"
            label="显示顺序"
            width="100"
            align="center"
        />
        <el-table-column
            prop="leader"
            label="负责人"
            min-width="120"
        />
        <el-table-column
            prop="phone"
            label="联系电话"
            min-width="130"
        />
        <el-table-column
            prop="status"
            label="部门状态"
            width="100"
            align="center"
        >
          <template #default="{ row }">
            <el-switch
                v-model="row.status"
                :active-value="1"
                :inactive-value="0"
                size="small"
                @change="handleStatusChange(row)"
            />
          </template>
        </el-table-column>
        <el-table-column
            prop="createTime"
            label="创建时间"
            min-width="160"
            align="center"
        />
        <el-table-column
            label="操作"
            width="110"
            fixed="right"
            align="center"
        >
          <template #default="{ row }">
            <div class="flex items-center justify-center gap-1">
              <el-tooltip
                  content="新增子级"
                  placement="top"
                  :enterable="false"
              >
                <el-button
                    v-hasPermi="['sys:dept:add']"
                    type="primary"
                    link
                    size="small"
                    @click="openDialog(row, true)"
                >
                  <el-icon :size="14">
                    <Plus/>
                  </el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip
                  content="修改"
                  placement="top"
                  :enterable="false"
              >
                <el-button
                    v-hasPermi="['sys:dept:edit']"
                    type="primary"
                    link
                    size="small"
                    @click="openDialog(row)"
                >
                  <el-icon :size="14">
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
                    v-hasPermi="['sys:dept:delete']"
                    type="danger"
                    link
                    size="small"
                    @click="handleDelete(row)"
                >
                  <el-icon :size="14">
                    <Delete/>
                  </el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 4. 新增/修改部门弹窗 -->
    <el-dialog
        v-model="dialogVisible"
        :title="dialogTitle"
        width="500px"
        destroy-on-close
        class="compact-edit-dialog"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-width="100px"
          class="compact-edit-form"
          size="small"
      >
        <el-form-item
            label="上级部门"
            prop="parentId"
        >
          <el-tree-select
              v-model="form.parentId"
              :data="deptOptions"
              :props="treeSelectProps"
              node-key="deptId"
              placeholder="选择上级部门"
              check-strictly
              default-expand-all
              class="w-full"
          />
        </el-form-item>
        <el-form-item
            label="部门名称"
            prop="deptName"
        >
          <el-input
              v-model="form.deptName"
              maxlength="100"
              placeholder="请输入部门名称..."
          />
        </el-form-item>
        <el-form-item
            label="显示顺序"
            prop="sortOrder"
        >
          <el-input-number
              v-model="form.sortOrder"
              :min="0"
              :max="999"
              class="!w-40"
          />
        </el-form-item>
        <el-form-item
            label="负责人"
            prop="leader"
        >
          <el-input
              v-model="form.leader"
              maxlength="50"
              placeholder="请输入负责人名称..."
          />
        </el-form-item>
        <el-form-item
            label="联系电话"
            prop="phone"
        >
          <el-input
              v-model="form.phone"
              maxlength="20"
              placeholder="请输入联系电话..."
          />
        </el-form-item>
        <el-form-item label="部门状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">
              启用
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

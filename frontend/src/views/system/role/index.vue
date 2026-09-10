<script setup lang="ts">
import type {ElTree, FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  createRole,
  deleteRole,
  getRole,
  getRoleFormOptions,
  listRoles,
  type MenuTreeVO,
  type RoleQuery,
  type RoleVO,
  updateRole
} from '@/api/system'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'
import {useUserStore} from '@/store/modules/user'

// ==========================================
// 1. 数据定义与初始化
// ==========================================
const loading = ref(false)
const total = ref(0)
const roleList = ref<RoleVO[]>([])
const menuTree = ref<MenuTreeVO[]>([])

// 搜索条件
const queryParams = reactive<PageRequest<RoleQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    roleName: '',
    roleCode: '',
    status: undefined
  }
})

// ==========================================
// 2. 表单与树型关联控制
// ==========================================
const dialogVisible = ref(false)
const dialogTitle = ref('新增角色')
const formRef = ref<FormInstance>()
const menuTreeRef = ref<InstanceType<typeof ElTree>>()

const form = reactive({
  roleId: undefined as number | undefined,
  roleName: '',
  roleCode: '',
  status: 1,
  version: 0,
  menuIds: [] as number[]
})

const rules = reactive<FormRules>({
  roleName: [
    {required: true, message: '角色名称不能为空', trigger: 'blur'},
    {min: 2, max: 50, message: '角色名称长度在 2 到 50 个字符之间', trigger: 'blur'}
  ],
  roleCode: [
    {required: true, message: '角色编码不能为空', trigger: 'blur'},
    {min: 2, max: 50, message: '角色编码长度在 2 到 50 个字符之间', trigger: 'blur'},
    {pattern: /^[A-Z_a-z0-9]+$/, message: '角色编码只能包含字母、数字及下划线', trigger: 'blur'}
  ]
})

// Tree 节点属性映射
const menuTreeProps = {
  children: 'children',
  label: 'menuName'
}

// ==========================================
// 3. 业务逻辑方法
// ==========================================

// 加载角色分页列表
async function loadRoles() {
  loading.value = true
  try {
    const res = await listRoles(queryParams)
    if (res && res.data) {
      roleList.value = res.data.list || []
      total.value = res.data.total || 0
    }
  } catch (err) {
    console.error('加载角色列表失败', err)
  } finally {
    loading.value = false
  }
}

// 加载菜单树（分配权限用）
async function loadMenus() {
  try {
    const res = await getRoleFormOptions()
    if (res && res.data) {
      menuTree.value = res.data.menus || []
    }
  } catch (err) {
    console.error('加载菜单树失败', err)
  }
}

// 查询与重置
function handleQuery() {
  queryParams.pageNum = 1
  loadRoles()
}

function handleReset() {
  queryParams.query!.roleName = ''
  queryParams.query!.roleCode = ''
  queryParams.query!.status = undefined
  queryParams.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadRoles()
}

// 分页控制
function handleSizeChange(size: number) {
  queryParams.pageSize = size
  loadRoles()
}

function handleCurrentChange(page: number) {
  queryParams.pageNum = page
  loadRoles()
}

// 展开/收起全部菜单节点
const isExpandAll = ref(true)

function handleExpandCollapseAll() {
  isExpandAll.value = !isExpandAll.value
  const nodes = menuTreeRef.value?.store.nodesMap
  if (nodes) {
    for (const key in nodes) {
      nodes[key].expanded = isExpandAll.value
    }
  }
}

// 菜单节点全选/全不选
const isSelectAll = ref(false)

function handleSelectUnselectAll() {
  isSelectAll.value = !isSelectAll.value
  if (isSelectAll.value) {
    menuTreeRef.value?.setCheckedNodes(menuTree.value)
  } else {
    menuTreeRef.value?.setCheckedKeys([])
  }
}

// 开启新增/编辑角色弹窗
async function openDialog(row?: RoleVO) {
  dialogVisible.value = true
  formRef.value?.resetFields()
  isSelectAll.value = false

  // 1. 初始化空表单
  form.roleId = undefined
  form.roleName = ''
  form.roleCode = ''
  form.status = 1
  form.version = 0
  form.menuIds = []

  // 2. 如果是编辑，拉取角色最新的明细（含已绑定的 menuIds 集合）
  if (row) {
    dialogTitle.value = '编辑角色'
    form.roleId = row.roleId
    form.roleName = row.roleName
    form.roleCode = row.roleCode
    form.status = row.status
    form.version = row.version

    try {
      const res = await getRole(row.roleId)
      if (res && res.data) {
        form.menuIds = res.data.menuIds || []
        // nextTick 保证 Tree 已经挂载好
        nextTick(() => {
          if (menuTreeRef.value) {
            // 1. 先清空所有选中
            menuTreeRef.value.setCheckedKeys([])
            // 2. 仅勾选真正的末端叶子节点
            // 如果勾选了父节点，Element Tree 的 check-strictly 默认 false 会导致其下所有子节点被全选
            form.menuIds.forEach((id) => {
              const node = menuTreeRef.value?.getNode(id)
              if (node && (!node.data.children || node.data.children.length === 0)) {
                menuTreeRef.value?.setChecked(id, true, false)
              }
            })
          }
        })
      }
    } catch (err) {
      console.error('拉取角色关联菜单失败', err)
    }
  } else {
    dialogTitle.value = '新增角色'
    nextTick(() => {
      menuTreeRef.value?.setCheckedKeys([])
    })
  }
}

// ... 业务逻辑方法

// 提交角色数据
async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const userStore = useUserStore()

  // 收集已选中的菜单节点，包含全选和半选状态（确保父级菜单节点也一并上传，否则前端面包屑及路由展示会缺失）
  const checkedKeys = menuTreeRef.value?.getCheckedKeys() || []
  const halfCheckedKeys = menuTreeRef.value?.getHalfCheckedKeys() || []
  form.menuIds = [...checkedKeys, ...halfCheckedKeys] as number[]

  try {
    if (form.roleId !== undefined) {
      // 修改
      await updateRole(form.roleId, {
        roleName: form.roleName,
        roleCode: form.roleCode,
        status: form.status,
        version: form.version,
        menuIds: form.menuIds
      })
      ElMessage.success('角色更新成功')
    } else {
      // 新增
      await createRole({
        roleName: form.roleName,
        roleCode: form.roleCode,
        status: form.status,
        menuIds: form.menuIds
      })
      ElMessage.success('角色创建成功')
    }

    // 👑 自动同步当前管理员的最新权限（如果是管理员自己修改了角色）
    await userStore.syncProfile()

    dialogVisible.value = false
    loadRoles()
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err))
  }
}

// 删除角色
function handleDelete(row: RoleVO) {
  // superAdmin 角色具有唯一特权在代码底层硬写死旁路
  if (row.roleCode === 'superAdmin') {
    ElMessage.warning('系统内置 superAdmin 角色属于安全策略核心，无法被删除！')
    return
  }

  ElMessageBox.confirm(`确认删除角色「${row.roleName} (${row.roleCode})」吗？删除后绑定该角色的账户将失去关联权限！`, '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteRole(row.roleId)
      ElMessage.success('角色已成功删除')
      loadRoles()
    } catch (err: unknown) {
      ElMessage.error(getErrorMessage(err, '删除失败'))
    }
  }).catch(() => {
  })
}

// 状态快速切换
async function handleStatusChange(row: RoleVO) {
  if (row.roleCode === 'superAdmin') {
    row.status = 1 // 强锁启用
    ElMessage.warning('superAdmin 超级管理员角色状态必须长期保持开启！')
    return
  }

  try {
    // 拉取最新的角色 menuIds 并随状态一并同步
    const detail = await getRole(row.roleId)
    const menuIds = detail?.data?.menuIds || []

    await updateRole(row.roleId, {
      roleName: row.roleName,
      roleCode: row.roleCode,
      status: row.status,
      version: row.version,
      menuIds
    })
    ElMessage.success(`角色状态已成功切换为: ${row.status === 1 ? '正常' : '禁用'}`)
    loadRoles() // 🚩 遵从 UI 规范：操作成功后刷新数据
  } catch (err: unknown) {
    row.status = row.status === 1 ? 0 : 1 // 切换失败回滚
    ElMessage.error(getErrorMessage(err, '角色状态切换失败'))
  }
}

// 初始化挂载
onMounted(() => {
  loadRoles()
  loadMenus()
})
</script>

<template>
  <div class="flex-1 flex flex-col min-h-0 overflow-hidden bg-white p-3">
    <!-- 1. 条件搜索区 (紧凑型，下设细分割线) -->
    <el-form
        :inline="true"
        :model="queryParams"
        class="compact-query-form mb-2 shrink-0"
        @submit.prevent="handleQuery"
    >
      <el-form-item label="角色名称">
        <el-input
            v-model="queryParams.query!.roleName"
            placeholder="请输入角色名称"
            size="small"
            clearable
            class="!w-44"
        />
      </el-form-item>
      <el-form-item label="权限编码">
        <el-input
            v-model="queryParams.query!.roleCode"
            placeholder="请输入角色编码"
            size="small"
            clearable
            class="!w-44"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
            v-model="queryParams.query!.status"
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
              label="禁用"
              :value="0"
          />
        </el-select>
      </el-form-item>
      <el-form-item class="!ml-auto">
        <el-button
            v-hasPermi="['sys:role:list']"
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
            v-hasPermi="['sys:role:list']"
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

    <!-- 2. 工具栏与操作区 -->
    <div class="compact-action-toolbar">
      <el-button
          v-hasPermi="['sys:role:add']"
          type="primary"
          size="small"
          @click="openDialog()"
      >
        <el-icon class="mr-1">
          <Plus/>
        </el-icon>
        新增角色
      </el-button>
    </div>

    <!-- 3. 数据表格 -->
    <div class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="roleList"
          border
          stripe
          size="small"
          height="100%"
      >
        <el-table-column
            prop="roleName"
            label="角色名称"
            min-width="150"
        />
        <el-table-column
            prop="roleCode"
            label="角色权限编码"
            min-width="150"
        >
          <template #default="{ row }">
            <el-tag
                :type="row.roleCode === 'superAdmin' ? 'danger' : 'info'"
                size="small"
            >
              {{ row.roleCode }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
            prop="status"
            label="角色状态"
            width="100"
            align="center"
        >
          <template #default="{ row }">
            <el-switch
                v-model="row.status"
                :active-value="1"
                :inactive-value="0"
                size="small"
                :disabled="row.roleCode === 'superAdmin'"
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
            width="80"
            fixed="right"
            align="center"
        >
          <template #default="{ row }">
            <div class="flex items-center justify-center gap-1">
              <el-tooltip
                  content="修改"
                  placement="top"
                  :enterable="false"
              >
                <el-button
                    v-hasPermi="['sys:role:edit']"
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
                    v-hasPermi="['sys:role:delete']"
                    type="danger"
                    link
                    size="small"
                    :disabled="row.roleCode === 'superAdmin'"
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

    <!-- 4. 分页区 -->
    <div class="compact-pagination">
      <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :total="total"
          :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
          size="small"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
      />
    </div>

    <!-- 5. 新增/编辑角色弹窗 -->
    <el-dialog
        v-model="dialogVisible"
        :title="dialogTitle"
        width="560px"
        destroy-on-close
        class="compact-edit-dialog"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-width="90px"
          class="compact-edit-form"
          size="small"
      >
        <el-form-item
            label="角色名称"
            prop="roleName"
        >
          <el-input
              v-model="form.roleName"
              placeholder="请输入角色展示名称，如：高级系统维护员..."
          />
        </el-form-item>
        <el-form-item
            label="角色编码"
            prop="roleCode"
        >
          <el-input
              v-model="form.roleCode"
              :disabled="form.roleId !== undefined && form.roleCode === 'superAdmin'"
              placeholder="输入系统唯一全局标志符，如：ROLE_MAINTAIN..."
          />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-radio-group
              v-model="form.status"
              :disabled="form.roleCode === 'superAdmin'"
          >
            <el-radio :value="1">
              正常启用
            </el-radio>
            <el-radio :value="0">
              禁用挂起
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="功能权限">
          <div class="w-full flex flex-col border border-gray-200 rounded-md p-3 bg-gray-50/50">
            <!-- 树辅助操作按钮 -->
            <div class="flex gap-2 mb-3 border-b border-gray-200/60 pb-2">
              <el-button
                  size="small"
                  type="primary"
                  plain
                  @click="handleExpandCollapseAll"
              >
                {{ isExpandAll ? '收起全部' : '展开全部' }}
              </el-button>
              <el-button
                  size="small"
                  type="success"
                  plain
                  @click="handleSelectUnselectAll"
              >
                {{ isSelectAll ? '全不选' : '全选' }}
              </el-button>
            </div>
            <!-- 树形菜单 -->
            <div class="bg-white rounded border border-gray-100 p-2 max-h-60 overflow-y-auto">
              <el-tree
                  ref="menuTreeRef"
                  :data="menuTree"
                  :props="menuTreeProps"
                  show-checkbox
                  node-key="menuId"
                  default-expand-all
                  check-on-click-node
                  class="role-menu-tree"
              />
            </div>
          </div>
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

<style scoped>
.role-menu-tree :deep(.el-tree-node__content) {
  border-radius: var(--ui-radius-sm);
  padding-top: var(--ui-space-1);
  padding-bottom: var(--ui-space-1);
  transition: colors 150ms;
}

.role-menu-tree :deep(.el-tree-node__content:hover) {
  background-color: color-mix(in srgb, var(--ui-color-primary-light) 40%, transparent);
  color: var(--ui-color-primary);
}
</style>

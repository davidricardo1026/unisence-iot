<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  createUser,
  deleteUser,
  type DeptTreeVO,
  getUserFormOptions,
  listUsers,
  resetPassword,
  type RoleVO,
  updateUser,
  type UserQuery,
  type UserVO
} from '@/api/system'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'
import {sha256} from '@/utils/crypto'
import {rejectInvisibleUnicode} from '@/utils/validation'

// ==========================================
// 1. 数据定义与初始化
// ==========================================
const loading = ref(false)
const total = ref(0)
const userList = ref<UserVO[]>([])
const deptTree = ref<DeptTreeVO[]>([])
const roleList = ref<RoleVO[]>([])

// 左侧部门过滤
const deptFilterText = ref('')
const deptTreeRef = ref()

// 右侧查询参数
const queryParams = reactive<PageRequest<UserQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    userCode: '',
    userName: '',
    phone: '',
    status: undefined,
    deptId: undefined
  }
})

// 默认部门 Tree 属性配置
const defaultProps = {
  children: 'children',
  label: 'deptName',
  value: 'deptId'
}

// ==========================================
// 2. 表单与弹窗控制
// ==========================================
const userDialogVisible = ref(false)
const userDialogTitle = ref('新增用户')
const userFormRef = ref<FormInstance>()

const userForm = reactive({
  userId: undefined as number | undefined,
  userCode: '',
  userName: '',
  phone: '',
  deptId: undefined as number | undefined,
  password: '',
  status: 1,
  version: 0,
  roleIds: [] as number[]
})

const userRules = reactive<FormRules>({
  userCode: [
    {required: true, message: '请输入用户编码', trigger: 'blur'},
    {min: 3, max: 30, message: '用户编码长度在 3 到 30 个字符之间', trigger: 'blur'},
    {validator: rejectInvisibleUnicode, trigger: ['blur', 'change']}
  ],
  userName: [
    {required: true, message: '请输入用户名称', trigger: 'blur'},
    {validator: rejectInvisibleUnicode, trigger: ['blur', 'change']}
  ],
  deptId: [
    {required: true, message: '请选择所属部门', trigger: 'change'}
  ],
  password: [
    {required: true, message: '请输入初始密码', trigger: 'blur'},
    {min: 6, max: 30, message: '密码长度至少为 6 个字符', trigger: 'blur'},
    {validator: rejectInvisibleUnicode, trigger: ['blur', 'change']}
  ],
  phone: [
    {pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号码', trigger: 'blur'},
    {validator: rejectInvisibleUnicode, trigger: ['blur', 'change']}
  ]
})

// 重置密码弹窗
const pwdDialogVisible = ref(false)
const pwdFormRef = ref<FormInstance>()
const pwdForm = reactive({
  userId: undefined as number | undefined,
  userCode: '',
  password: ''
})
const pwdRules = reactive<FormRules>({
  password: [
    {required: true, message: '请输入新密码', trigger: 'blur'},
    {min: 6, max: 30, message: '密码长度至少为 6 个字符', trigger: 'blur'},
    {validator: rejectInvisibleUnicode, trigger: ['blur', 'change']}
  ]
})

// ==========================================
// 3. 业务逻辑方法
// ==========================================

// 加载部门树
async function fetchFormOptions() {
  try {
    const res = await getUserFormOptions()
    if (res && res.data) {
      deptTree.value = res.data.departments || []
      roleList.value = res.data.roles || []
    }
    return true
  } catch (err) {
    ElMessage.error(getErrorMessage(err, '加载用户编辑选项失败'))
    return false
  }
}

// 加载用户分页列表
async function fetchUsers() {
  loading.value = true
  try {
    const res = await listUsers(queryParams)
    if (res && res.data) {
      userList.value = res.data.list || []
      total.value = res.data.total || 0
    }
  } catch (err) {
    console.error('加载用户列表失败', err)
  } finally {
    loading.value = false
  }
}

// 部门树节点过滤过滤方法
function filterDeptNode(value: string, data: DeptTreeVO) {
  if (!value) return true
  return data.deptName.includes(value)
}

// 监听部门过滤关键字
watch(deptFilterText, (val) => {
  deptTreeRef.value?.filter(val)
})

// 部门节点被点击
function handleDeptClick(data: DeptTreeVO) {
  queryParams.query!.deptId = data.deptId
  queryParams.pageNum = 1
  fetchUsers()
}

// 清除部门选择
function clearDeptSelection() {
  queryParams.query!.deptId = undefined
  queryParams.pageNum = 1
  fetchUsers()
}

// 查询与重置
function handleQuery() {
  queryParams.pageNum = 1
  fetchUsers()
}

function handleReset() {
  queryParams.query!.userCode = ''
  queryParams.query!.userName = ''
  queryParams.query!.phone = ''
  queryParams.query!.status = undefined
  queryParams.query!.deptId = undefined
  queryParams.pageNum = PAGE_DEFAULT.PAGE_NUM
  fetchUsers()
}

function getUserRoleNames(user: UserVO) {
  return user.roleNames || []
}

// 分页变化
function handleSizeChange(size: number) {
  queryParams.pageSize = size
  fetchUsers()
}

function handleCurrentChange(page: number) {
  queryParams.pageNum = page
  fetchUsers()
}

// 开启新增/编辑用户弹窗
async function handleOpenUserDialog(row?: UserVO) {
  if (!await fetchFormOptions()) return
  userDialogVisible.value = true
  userFormRef.value?.resetFields()

  if (row) {
    userDialogTitle.value = '编辑用户'
    userForm.userId = row.userId
    userForm.userCode = row.userCode
    userForm.userName = row.userName
    userForm.phone = row.phone || ''
    userForm.deptId = row.deptId
    userForm.status = row.status
    userForm.version = row.version
    userForm.roleIds = row.roleIds || []
    userForm.password = '' // 编辑时不填密码
  } else {
    userDialogTitle.value = '新增用户'
    userForm.userId = undefined
    userForm.userCode = ''
    userForm.userName = ''
    userForm.phone = ''
    userForm.deptId = undefined
    userForm.password = ''
    userForm.status = 1
    userForm.version = 0
    userForm.roleIds = []
  }
}

// 提交新增/编辑表单
async function handleUserSubmit() {
  if (!userFormRef.value) return
  const valid = await userFormRef.value.validate().catch(() => false)
  if (!valid) return

  try {
    if (userForm.userId) {
      // 编辑用户
      await updateUser(userForm.userId, {
        userName: userForm.userName,
        phone: userForm.phone || undefined,
        deptId: userForm.deptId!,
        status: userForm.status,
        version: userForm.version,
        roleIds: userForm.roleIds
      })
      ElMessage.success('用户更新成功')
    } else {
      // 新增用户：前端对初始密码进行 SHA-256 哈希
      const hashedPwd = await sha256(userForm.password)
      await createUser({
        userCode: userForm.userCode,
        userName: userForm.userName,
        phone: userForm.phone || undefined,
        deptId: userForm.deptId!,
        password: hashedPwd,
        status: userForm.status,
        roleIds: userForm.roleIds
      })
      ElMessage.success('用户创建成功')
    }
    userDialogVisible.value = false
    fetchUsers()
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err))
  }
}

// 状态快速切换切换
async function handleStatusChange(row: UserVO) {
  try {
    await updateUser(row.userId, {
      userName: row.userName,
      phone: row.phone || undefined,
      deptId: row.deptId,
      status: row.status,
      version: row.version,
      roleIds: row.roleIds
    })
    ElMessage.success(`用户状态已切换为: ${row.status === 1 ? '正常' : '停用'}`)
    fetchUsers() // 🚩 遵从 UI 规范：操作成功后刷新数据
  } catch (err: unknown) {
    row.status = row.status === 1 ? 0 : 1 // 失败回滚
    ElMessage.error(getErrorMessage(err, '状态切换失败'))
  }
}

// 删除用户
function handleDeleteUser(row: UserVO) {
  ElMessageBox.confirm(`确认删除用户「${row.userName} (账号: ${row.userCode})」吗？删除后不可恢复！`, '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteUser(row.userId)
      ElMessage.success('删除成功')
      fetchUsers()
    } catch (err: unknown) {
      ElMessage.error(getErrorMessage(err, '删除失败'))
    }
  }).catch(() => {
  })
}

// 打开重置密码弹窗
function handleOpenResetPwd(row: UserVO) {
  pwdDialogVisible.value = true
  pwdForm.userId = row.userId
  pwdForm.userCode = row.userCode
  pwdForm.password = ''
  pwdFormRef.value?.resetFields()
}

// 提交重置密码
async function handleResetPwdSubmit() {
  if (!pwdFormRef.value) return
  const valid = await pwdFormRef.value.validate().catch(() => false)
  if (!valid) return

  try {
    // 前端 SHA-256 离散
    const hashedPwd = await sha256(pwdForm.password)
    await resetPassword(pwdForm.userId!, {password: hashedPwd})
    ElMessage.success(`用户 ${pwdForm.userCode} 密码已成功重置`)
    pwdDialogVisible.value = false
    fetchUsers() // 🚩 遵从 UI 规范：操作成功后刷新数据
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err, '密码重置失败'))
  }
}

// 初始化加载
onMounted(() => {
  fetchUsers()
})
</script>

<template>
  <div class="flex flex-row flex-1 h-full min-h-0 gap-3 overflow-hidden bg-white p-3">
    <!-- 左侧：部门组织树 -->
    <aside class="flex w-60 shrink-0 flex-col min-h-0 self-stretch rounded-lg border border-gray-100 bg-gray-50/50 p-3">
      <div class="mb-3 flex shrink-0 items-center justify-between border-b border-gray-100 pb-1.5">
        <span class="flex items-center gap-1.5 text-xs font-bold text-gray-700">
          <el-icon class="text-blue-500"><Folder/></el-icon>部门组织树
        </span>
        <el-button
            v-if="queryParams.query?.deptId"
            link
            type="primary"
            size="small"
            @click="clearDeptSelection"
        >
          清除选择
        </el-button>
      </div>
      <el-input
          v-model="deptFilterText"
          placeholder="输入部门名称过滤..."
          size="small"
          clearable
          class="mb-2 shrink-0"
      >
        <template #prefix>
          <el-icon>
            <Search/>
          </el-icon>
        </template>
      </el-input>
      <el-scrollbar class="min-h-0 flex-1">
        <el-tree
            ref="deptTreeRef"
            :data="deptTree"
            :props="defaultProps"
            node-key="deptId"
            :filter-node-method="filterDeptNode"
            default-expand-all
            highlight-current
            :expand-on-click-node="false"
            class="filter-tree"
            @node-click="handleDeptClick"
        />
      </el-scrollbar>
    </aside>

    <!-- 右侧：上查询 + 下表格 -->
    <div class="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
      <!-- 上：查询与工具栏 -->
      <section class="mb-2 shrink-0">
        <el-form
            :inline="true"
            :model="queryParams"
            class="compact-query-form"
            @submit.prevent="handleQuery"
        >
          <el-form-item label="用户编码">
            <el-input
                v-model="queryParams.query!.userCode"
                placeholder="用户编码"
                size="small"
                clearable
                class="!w-36"
            />
          </el-form-item>
          <el-form-item label="用户名称">
            <el-input
                v-model="queryParams.query!.userName"
                placeholder="用户名称"
                size="small"
                clearable
                class="!w-36"
            />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input
                v-model="queryParams.query!.phone"
                placeholder="手机号"
                size="small"
                clearable
                class="!w-36"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select
                v-model="queryParams.query!.status"
                placeholder="状态"
                size="small"
                clearable
                class="!w-24"
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
                v-hasPermi="['sys:user:list']"
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
                v-hasPermi="['sys:user:list']"
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

        <div class="mt-2 flex items-center justify-between">
          <el-button
              v-hasPermi="['sys:user:add']"
              type="primary"
              size="small"
              @click="handleOpenUserDialog()"
          >
            <el-icon class="mr-1">
              <Plus/>
            </el-icon>
            新增用户
          </el-button>
          <span class="text-[11px] text-gray-400">若需重置密码，可在对应账户操作栏进行操作。</span>
        </div>
      </section>

      <!-- 下：表格与分页 -->
      <section class="flex min-h-0 flex-1 flex-col overflow-hidden">
        <div class="compact-table-region">
          <el-table
              v-loading="loading"
              :data="userList"
              border
              stripe
              size="small"
              height="100%"
              row-key="userId"
          >
            <el-table-column
                prop="userCode"
                label="用户编码/登录账号"
                min-width="120"
            />
            <el-table-column
                prop="userName"
                label="用户名称"
                min-width="120"
            />
            <el-table-column
                prop="deptName"
                label="所属部门"
                min-width="130"
            >
              <template #default="{ row }">
                <span class="font-medium text-gray-600">{{ row.deptName || '未归属部门' }}</span>
              </template>
            </el-table-column>
            <el-table-column
                prop="phone"
                label="联系电话"
                min-width="120"
            />
            <el-table-column
                label="角色"
                min-width="170"
            >
              <template #default="{ row }">
                <div
                    v-if="getUserRoleNames(row).length"
                    class="flex flex-wrap gap-1"
                >
                  <el-tag
                      v-for="roleName in getUserRoleNames(row)"
                      :key="roleName"
                      size="small"
                      effect="plain"
                  >
                    {{ roleName }}
                  </el-tag>
                </div>
                <span
                    v-else
                    class="text-gray-400"
                >无角色</span>
              </template>
            </el-table-column>
            <el-table-column
                prop="status"
                label="帐号状态"
                width="100"
                align="center"
            >
              <template #default="{ row }">
                <el-switch
                    v-model="row.status"
                    :active-value="1"
                    :inactive-value="0"
                    size="small"
                    :disabled="row.userCode === 'superAdmin'"
                    @change="handleStatusChange(row)"
                />
              </template>
            </el-table-column>
            <el-table-column
                prop="createTime"
                label="创建时间"
                min-width="160"
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
                      content="编辑"
                      placement="top"
                      :enterable="false"
                  >
                    <el-button
                        v-hasPermi="['sys:user:edit']"
                        type="primary"
                        link
                        size="small"
                        :disabled="row.userCode === 'superAdmin'"
                        @click="handleOpenUserDialog(row)"
                    >
                      <el-icon :size="14">
                        <Edit/>
                      </el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip
                      content="重置密码"
                      placement="top"
                      :enterable="false"
                  >
                    <el-button
                        v-hasPermi="['sys:user:resetPwd']"
                        type="warning"
                        link
                        size="small"
                        :disabled="row.userCode === 'superAdmin'"
                        @click="handleOpenResetPwd(row)"
                    >
                      <el-icon :size="14">
                        <Key/>
                      </el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip
                      content="删除"
                      placement="top"
                      :enterable="false"
                  >
                    <el-button
                        v-hasPermi="['sys:user:delete']"
                        type="danger"
                        link
                        size="small"
                        :disabled="row.userCode === 'superAdmin'"
                        @click="handleDeleteUser(row)"
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
      </section>
    </div>
    <!-- 3. 新增/编辑用户对话框 -->
    <el-dialog
        v-model="userDialogVisible"
        :title="userDialogTitle"
        width="520px"
        destroy-on-close
        class="compact-edit-dialog"
    >
      <el-form
          ref="userFormRef"
          :model="userForm"
          :rules="userRules"
          label-width="100px"
          class="compact-edit-form"
          size="small"
      >
        <el-form-item
            label="登录账号"
            prop="userCode"
        >
          <el-input
              v-model="userForm.userCode"
              maxlength="50"
              :disabled="userForm.userId !== undefined"
              placeholder="请输入唯一登录账号..."
          />
        </el-form-item>
        <el-form-item
            label="用户名称"
            prop="userName"
        >
          <el-input
              v-model="userForm.userName"
              maxlength="50"
              placeholder="请输入用户名称..."
          />
        </el-form-item>
        <el-form-item
            label="所属部门"
            prop="deptId"
        >
          <el-tree-select
              v-model="userForm.deptId"
              :data="deptTree"
              :props="defaultProps"
              node-key="deptId"
              placeholder="请选择部门"
              check-strictly
              default-expand-all
              class="w-full"
          />
        </el-form-item>
        <el-form-item
            label="联系电话"
            prop="phone"
        >
          <el-input
              v-model="userForm.phone"
              maxlength="20"
              placeholder="请输入手机号码..."
          />
        </el-form-item>
        <el-form-item
            v-if="userForm.userId === undefined"
            label="初始密码"
            prop="password"
        >
          <el-input
              v-model="userForm.password"
              type="password"
              show-password
              placeholder="请输入不少于 6 位的初始密码..."
          />
        </el-form-item>
        <el-form-item
            label="绑定角色"
            prop="roleIds"
        >
          <el-select
              v-model="userForm.roleIds"
              multiple
              placeholder="请选择关联角色"
              class="w-full"
          >
            <el-option
                v-for="role in roleList"
                :key="role.roleId"
                :label="role.roleName"
                :value="role.roleId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-radio-group v-model="userForm.status">
            <el-radio :value="1">
              正常启用
            </el-radio>
            <el-radio :value="0">
              停用休眠
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
            size="small"
            @click="userDialogVisible = false"
        >
          取消
        </el-button>
        <el-button
            size="small"
            type="primary"
            @click="handleUserSubmit"
        >
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 4. 重置密码对话框 -->
    <el-dialog
        v-model="pwdDialogVisible"
        title="重置用户密码"
        width="440px"
        destroy-on-close
        class="compact-edit-dialog"
    >
      <el-form
          ref="pwdFormRef"
          :model="pwdForm"
          :rules="pwdRules"
          label-width="100px"
          class="compact-edit-form"
          size="small"
      >
        <el-form-item label="用户编码">
          <el-input
              :value="pwdForm.userCode"
              disabled
          />
        </el-form-item>
        <el-form-item
            label="新密码"
            prop="password"
        >
          <el-input
              v-model="pwdForm.password"
              type="password"
              show-password
              placeholder="请输入不少于 6 位的新密码..."
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
            size="small"
            @click="pwdDialogVisible = false"
        >
          取消
        </el-button>
        <el-button
            size="small"
            type="primary"
            @click="handleResetPwdSubmit"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-tree :deep(.el-tree-node__content) {
  border-radius: var(--ui-radius-md);
  padding-top: var(--ui-space-1);
  padding-bottom: var(--ui-space-1);
  transition: all 150ms cubic-bezier(0.4, 0, 0.2, 1);
  color: var(--ui-text-regular);
}

.filter-tree :deep(.el-tree-node__content:hover) {
  background-color: color-mix(in srgb, var(--ui-color-primary-light) 50%, transparent);
  color: var(--ui-color-primary);
}

.filter-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background-color: var(--ui-color-primary-light);
  color: var(--ui-color-primary);
  font-weight: 500;
}
</style>

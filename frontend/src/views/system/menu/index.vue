<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  batchUpdateMenuVisibility,
  createMenu,
  deleteMenu,
  getMenuTree,
  type MenuTreeVO,
  type MenuType,
  updateMenu
} from '@/api/system'
import {useUserStore} from '@/store/modules/user'

// ==========================================
// 1. 数据定义与初始化
// ==========================================
const loadingLeft = ref(false)
const loadingRight = ref(false)
const saving = ref(false)

// 左侧：配置树数据源（全量，不受搜索影响）
const leftTreeData = ref<MenuTreeVO[]>([])
const menuFilterText = ref('') // 左侧树本地搜索
const menuTreeRef = ref()

// 右侧：查询表格数据源（受 queryParams 驱动）
const rightTableData = ref<MenuTreeVO[]>([])
const queryParams = reactive({
  menuName: '',
  perms: '',
  isVisible: undefined as number | undefined
})

const defaultProps = {
  children: 'children',
  label: 'menuName'
}

// ==========================================
// 2. 业务逻辑方法
// ==========================================

/** 加载左侧配置树（全量数据） */
async function fetchLeftTree() {
  loadingLeft.value = true
  try {
    const res = await getMenuTree() // 不传参，获取全量
    if (res && res.data) {
      leftTreeData.value = res.data

      // 👑 同步勾选状态：仅勾选可见的叶子节点（el-tree 联动会自动处理父级）
      const checked: number[] = []

      function collectChecked(list: MenuTreeVO[]) {
        list.forEach(item => {
          if (item.isVisible === 1) {
            if (!item.children || item.children.length === 0) {
              checked.push(item.menuId)
            } else {
              collectChecked(item.children)
            }
          }
        })
      }

      collectChecked(res.data)
      nextTick(() => {
        menuTreeRef.value?.setCheckedKeys(checked)
      })
    }
  } catch (err) {
    console.error('加载左侧菜单树失败', err)
  } finally {
    loadingLeft.value = false
  }
}

/** 加载右侧表格（带条件搜索） */
async function fetchRightTable() {
  loadingRight.value = true
  try {
    const res = await getMenuTree(queryParams)
    if (res && res.data) {
      rightTableData.value = res.data
    }
  } catch (err) {
    console.error('加载右侧表格失败', err)
  } finally {
    loadingRight.value = false
  }
}

/** 左侧树本地过滤 */
watch(menuFilterText, (val) => {
  menuTreeRef.value?.filter(val)
})

function filterNode(value: string, data: MenuTreeVO) {
  if (!value) return true
  return data.menuName.includes(value)
}

/** 👑 批量保存可见性更改 */
async function handleBatchSave() {
  if (!menuTreeRef.value) return

  try {
    await ElMessageBox.confirm('确定要保存当前的启用/禁用配置吗？这可能会影响导航栏及权限的全局生效状态。', '保存确认', {
      type: 'warning',
      confirmButtonText: '确定保存'
    })

    saving.value = true

    // 收集版本号（基于左侧最新数据）
    const allItems: { menuId: number, version: number }[] = []

    function traverse(list: MenuTreeVO[]) {
      list.forEach(node => {
        allItems.push({menuId: node.menuId, version: node.version})
        if (node.children) traverse(node.children)
      })
    }

    traverse(leftTreeData.value)

    // 获取可见 ID 集合（全选 + 半选）
    const visibleIds = [
      ...menuTreeRef.value.getCheckedKeys(),
      ...menuTreeRef.value.getHalfCheckedKeys()
    ]

    await batchUpdateMenuVisibility({
      visibleIds,
      items: allItems
    })

    ElMessage.success('保存成功')

    // 👑 保存成功后，联动刷新左右两边
    fetchLeftTree()
    fetchRightTable()
  } catch (err: unknown) {
    if (!isCancelError(err)) {
      ElMessage.error(getErrorMessage(err, '保存失败'))
    }
  } finally {
    saving.value = false
  }
}

/** 右侧查询与重置 */
function handleQuery() {
  fetchRightTable()
}

function handleReset() {
  queryParams.menuName = ''
  queryParams.perms = ''
  queryParams.isVisible = undefined
  fetchRightTable()
}

// ==========================================
// 3. 结构 CRUD（新增 / 修改 / 删除）
// ==========================================
const dialogVisible = ref(false)
const dialogTitle = ref('新增菜单')
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  menuId: undefined as number | undefined,
  parentId: 0,
  menuName: '',
  menuType: 'C' as MenuType,
  path: '',
  component: '',
  perms: '',
  icon: '',
  sortOrder: 0,
  isVisible: 1,
  version: 0
})

/** 各类型的合法父级类型；空数组表示只能作为顶级节点 */
const ALLOWED_PARENT_TYPES: Record<MenuType, MenuType[]> = {
  D: [],
  M: ['D'],
  C: ['D', 'M'],
  F: ['C']
}

const MENU_TYPE_LABELS: Record<MenuType, string> = {
  D: '模块',
  M: '目录',
  C: '菜单',
  F: '按钮'
}

// 字段随类型联动：按钮无路由/组件/图标，只有菜单需要组件，模块与目录不带权限标识
const isRootType = computed(() => form.menuType === 'D')
const showPath = computed(() => form.menuType !== 'F')
const showComponent = computed(() => form.menuType === 'C')
const showPerms = computed(() => form.menuType === 'C' || form.menuType === 'F')
const showIcon = computed(() => form.menuType !== 'F')
const permsRequired = computed(() => form.menuType === 'F')

const rules = computed<FormRules>(() => ({
  menuName: [
    {required: true, message: '菜单名称不能为空', trigger: 'blur'},
    {max: 100, message: '菜单名称长度不能超过 100 个字符', trigger: 'blur'}
  ],
  menuType: [{required: true, message: '菜单类型不能为空', trigger: 'change'}],
  // parentId 的“未选择”哨兵值是 0，而 async-validator 的 required 只拦 undefined/null/''，
  // 故用自定义校验拦 0；保留 required 以驱动标签红星
  parentId: isRootType.value
      ? []
      : [{
        required: true,
        validator: (_rule: unknown, value: number, callback: (error?: Error) => void) => {
          callback(value ? undefined : new Error('上级菜单不能为空'))
        },
        trigger: 'change'
      }],
  path: showPath.value
      ? [
        {required: true, message: '路由路径不能为空', trigger: 'blur'},
        {max: 255, message: '路由路径长度不能超过 255 个字符', trigger: 'blur'}
      ]
      : [],
  component: showComponent.value
      ? [
        {required: true, message: '组件路径不能为空', trigger: 'blur'},
        {max: 255, message: '组件路径长度不能超过 255 个字符', trigger: 'blur'}
      ]
      : [],
  perms: permsRequired.value
      ? [
        {required: true, message: '权限标识不能为空', trigger: 'blur'},
        {max: 100, message: '权限标识长度不能超过 100 个字符', trigger: 'blur'}
      ]
      : [{max: 100, message: '权限标识长度不能超过 100 个字符', trigger: 'blur'}]
}))

interface ParentOption extends MenuTreeVO {
  disabled: boolean
  children: ParentOption[]
}

/**
 * 按目标类型裁剪出可选父级树：
 * 非法类型的节点保留结构但置灰，编辑时整棵自身子树被剔除以防成环。
 */
function buildParentOptions(nodes: MenuTreeVO[], allowed: MenuType[], excludeId?: number): ParentOption[] {
  const result: ParentOption[] = []
  for (const node of nodes) {
    if (excludeId !== undefined && node.menuId === excludeId) continue
    const children = buildParentOptions(node.children ?? [], allowed, excludeId)
    const selectable = allowed.includes(node.menuType)
    if (selectable || children.length > 0) {
      result.push({...node, children, disabled: !selectable})
    }
  }
  return result
}

const parentOptions = computed<ParentOption[]>(() =>
    buildParentOptions(leftTreeData.value, ALLOWED_PARENT_TYPES[form.menuType], form.menuId)
)

const parentTreeProps = {
  children: 'children',
  label: 'menuName',
  disabled: 'disabled'
}

/** 收集树中所有可选（未置灰）的节点 ID */
function collectSelectableIds(nodes: ParentOption[], acc: number[] = []): number[] {
  for (const node of nodes) {
    if (!node.disabled) acc.push(node.menuId)
    collectSelectableIds(node.children, acc)
  }
  return acc
}

/** 类型切换时清理不适用字段，并校正已失效的父级选择 */
function handleTypeChange() {
  if (!showPath.value) form.path = ''
  if (!showComponent.value) form.component = ''
  if (!showPerms.value) form.perms = ''
  if (!showIcon.value) form.icon = ''

  if (isRootType.value) {
    form.parentId = 0
    return
  }
  if (!collectSelectableIds(parentOptions.value).includes(form.parentId)) {
    form.parentId = 0
  }
}

function openDialog(row?: MenuTreeVO) {
  form.menuId = row?.menuId
  form.parentId = row?.parentId ?? 0
  form.menuName = row?.menuName ?? ''
  form.menuType = row?.menuType ?? 'C'
  form.path = row?.path ?? ''
  form.component = row?.component ?? ''
  form.perms = row?.perms ?? ''
  form.icon = row?.icon ?? ''
  form.sortOrder = row?.sortOrder ?? 0
  form.isVisible = row?.isVisible ?? 1
  form.version = row?.version ?? 0

  dialogTitle.value = row ? '编辑菜单' : '新增菜单'
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const payload = {
    parentId: form.parentId,
    menuName: form.menuName,
    menuType: form.menuType,
    path: showPath.value ? form.path : '',
    component: showComponent.value ? form.component : '',
    perms: showPerms.value ? form.perms : '',
    icon: showIcon.value ? form.icon : '',
    sortOrder: form.sortOrder,
    isVisible: form.isVisible
  }

  submitting.value = true
  try {
    if (form.menuId !== undefined) {
      await updateMenu(form.menuId, {...payload, version: form.version})
      ElMessage.success('菜单更新成功')
    } else {
      await createMenu(payload)
      ElMessage.success('菜单创建成功')
    }
    dialogVisible.value = false
    await refreshAfterMutation()
  } catch (err: unknown) {
    ElMessage.error(getErrorMessage(err, '保存失败'))
  } finally {
    submitting.value = false
  }
}

function handleDelete(row: MenuTreeVO) {
  ElMessageBox.confirm(
      `确认删除「${row.menuName}」吗？删除后该菜单及其权限标识将立即对所有用户失效。`,
      '警告',
      {confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'}
  ).then(async () => {
    try {
      await deleteMenu(row.menuId)
      ElMessage.success('菜单已删除')
      await refreshAfterMutation()
    } catch (err: unknown) {
      ElMessage.error(getErrorMessage(err, '删除失败'))
    }
  }).catch((err: unknown) => {
    if (!isCancelError(err)) {
      console.error('删除菜单确认框异常', err)
    }
  })
}

/** 结构变更会影响当前登录者的导航与权限，需连同 profile 一起刷新 */
async function refreshAfterMutation() {
  await Promise.all([fetchLeftTree(), fetchRightTable()])
  try {
    await useUserStore().syncProfile()
  } catch (err: unknown) {
    console.warn('同步当前用户菜单权限失败', err)
  }
}

onMounted(() => {
  fetchLeftTree()
  fetchRightTable()
})
</script>

<template>
  <div class="flex flex-row flex-1 h-full min-h-0 gap-3 overflow-hidden bg-white p-3">
    <!-- 左侧：菜单树（可见性配置面板） -->
    <aside class="flex w-72 shrink-0 flex-col min-h-0 self-stretch rounded-lg border border-gray-100 bg-gray-50/50 p-3">
      <div class="mb-3 flex shrink-0 items-center justify-between border-b border-gray-100 pb-1.5">
        <span class="flex items-center gap-1.5 text-xs font-bold text-gray-700">
          <el-icon class="text-blue-500"><Folder/></el-icon>可见性深度配置
        </span>
        <el-button
            v-hasPermi="['sys:menu:edit']"
            type="primary"
            size="small"
            :loading="saving"
            @click="handleBatchSave"
        >
          <el-icon class="mr-1">
            <Check/>
          </el-icon>
          保存
        </el-button>
      </div>

      <el-input
          v-model="menuFilterText"
          placeholder="搜索配置菜单..."
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

      <el-scrollbar
          v-loading="loadingLeft"
          class="min-h-0 flex-1"
      >
        <el-tree
            ref="menuTreeRef"
            :data="leftTreeData"
            :props="defaultProps"
            node-key="menuId"
            show-checkbox
            :filter-node-method="filterNode"
            default-expand-all
            highlight-current
            :expand-on-click-node="false"
            class="filter-tree"
        >
          <template #default="{ data }">
            <div class="flex items-center gap-1 text-[11px]">
              <el-icon v-if="data.icon">
                <component :is="data.icon"/>
              </el-icon>
              <span>{{ data.menuName }}</span>
              <el-tag
                  v-if="data.menuType === 'D'"
                  size="small"
                  type="danger"
                  class="!text-[9px] !h-3 !leading-3 ml-1"
              >
                模块
              </el-tag>
              <el-tag
                  v-if="data.menuType === 'F'"
                  size="small"
                  type="info"
                  class="!text-[9px] !h-3 !leading-3 ml-1"
              >
                按钮
              </el-tag>
            </div>
          </template>
        </el-tree>
      </el-scrollbar>
    </aside>

    <!-- 右侧：原有菜单管理表格（独立查询） -->
    <div class="flex-1 flex flex-col min-h-0 overflow-hidden">
      <el-form
          :inline="true"
          :model="queryParams"
          class="compact-query-form border-b border-gray-100/80 pb-2 mb-2 shrink-0"
          @submit.prevent="handleQuery"
      >
        <el-form-item label="名称">
          <el-input
              v-model="queryParams.menuName"
              size="small"
              clearable
              class="!w-32"
              placeholder="展示名称..."
          />
        </el-form-item>
        <el-form-item label="标识">
          <el-input
              v-model="queryParams.perms"
              size="small"
              clearable
              class="!w-32"
              placeholder="权限标识..."
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
              v-model="queryParams.isVisible"
              placeholder="选择状态"
              size="small"
              clearable
              class="!w-24"
          >
            <el-option
                label="启用"
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

      <div class="compact-action-toolbar">
        <el-button
            v-hasPermi="['sys:menu:add']"
            type="primary"
            size="small"
            @click="openDialog()"
        >
          <el-icon class="mr-1">
            <Plus/>
          </el-icon>
          新增菜单
        </el-button>
      </div>

      <div class="compact-table-region">
        <el-table
            v-loading="loadingRight"
            :data="rightTableData"
            row-key="menuId"
            border
            size="small"
            stripe
            height="100%"
            :tree-props="{children: 'children'}"
        >
          <el-table-column
              prop="menuName"
              label="菜单名称"
              min-width="200"
          >
            <template #default="{ row }">
              <el-icon
                  v-if="row.icon"
                  class="mr-1"
              >
                <component :is="row.icon"/>
              </el-icon>
              <span :class="{'text-gray-400 italic': row.isVisible === 0}">{{ row.menuName }}</span>
            </template>
          </el-table-column>
          <el-table-column
              prop="menuType"
              label="类型"
              width="80"
              align="center"
          >
            <template #default="{ row }">
              <el-tag
                  v-if="row.menuType === 'D'"
                  type="danger"
                  size="small"
              >
                模块
              </el-tag>
              <el-tag
                  v-else-if="row.menuType === 'M'"
                  size="small"
              >
                目录
              </el-tag>
              <el-tag
                  v-else-if="row.menuType === 'C'"
                  type="success"
                  size="small"
              >
                菜单
              </el-tag>
              <el-tag
                  v-else
                  type="warning"
                  size="small"
              >
                按钮
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
              prop="perms"
              label="权限标识"
              min-width="150"
              show-overflow-tooltip
          />
          <el-table-column
              prop="isVisible"
              label="当前状态"
              width="80"
              align="center"
          >
            <template #default="{ row }">
              <el-tag
                  :type="row.isVisible === 1 ? 'success' : 'info'"
                  size="small"
                  class="!text-[10px]"
              >
                {{ row.isVisible === 1 ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
              prop="sortOrder"
              label="排序"
              width="70"
              align="center"
          />
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
                      v-hasPermi="['sys:menu:edit']"
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
                      v-hasPermi="['sys:menu:delete']"
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
    </div>

    <!-- 新增/编辑菜单弹窗 -->
    <el-dialog
        v-model="dialogVisible"
        :title="dialogTitle"
        width="620px"
        destroy-on-close
        class="compact-edit-dialog"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-width="88px"
          class="compact-edit-form"
          size="small"
      >
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item
                label="菜单类型"
                prop="menuType"
            >
              <el-select
                  v-model="form.menuType"
                  class="!w-full"
                  @change="handleTypeChange"
              >
                <el-option
                    v-for="(label, value) in MENU_TYPE_LABELS"
                    :key="value"
                    :label="label"
                    :value="value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item
                label="上级菜单"
                prop="parentId"
            >
              <el-input
                  v-if="isRootType"
                  model-value="顶级（模块无上级）"
                  disabled
              />
              <el-tree-select
                  v-else
                  v-model="form.parentId"
                  :data="parentOptions"
                  :props="parentTreeProps"
                  node-key="menuId"
                  check-strictly
                  default-expand-all
                  placeholder="请选择上级菜单"
                  class="!w-full"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item
                label="菜单名称"
                prop="menuName"
            >
              <el-input
                  v-model="form.menuName"
                  placeholder="如：设备台账"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item
                label="显示排序"
                prop="sortOrder"
            >
              <el-input-number
                  v-model="form.sortOrder"
                  :min="0"
                  :max="9999"
                  controls-position="right"
                  class="!w-full"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col
              v-if="showPath"
              :span="12"
          >
            <el-form-item
                label="路由路径"
                prop="path"
            >
              <el-input
                  v-model="form.path"
                  placeholder="如：/device 或 list"
              />
            </el-form-item>
          </el-col>
          <el-col
              v-if="showIcon"
              :span="12"
          >
            <el-form-item
                label="菜单图标"
                prop="icon"
            >
              <el-input
                  v-model="form.icon"
                  placeholder="Element Plus 图标名，如 Setting"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item
            v-if="showComponent"
            label="组件路径"
            prop="component"
        >
          <el-input
              v-model="form.component"
              placeholder="相对 views 的路径，如：device/list/index"
          />
        </el-form-item>

        <el-form-item
            v-if="showPerms"
            label="权限标识"
            prop="perms"
        >
          <el-input
              v-model="form.perms"
              :placeholder="permsRequired ? '按钮必填，如：iot:device:add' : '选填，如：iot:device:list'"
          />
        </el-form-item>

        <el-form-item label="显示状态">
          <el-radio-group v-model="form.isVisible">
            <el-radio :value="1">
              启用
            </el-radio>
            <el-radio :value="0">
              禁用
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
            :loading="submitting"
            @click="handleSubmit"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-tree :deep(.el-tree-node__content) {
  border-radius: var(--ui-radius-sm);
  padding: var(--ui-space-1) 0;
  transition: all 150ms;
}

.filter-tree :deep(.el-tree-node__content:hover) {
  background-color: color-mix(in srgb, var(--ui-color-primary-light) 50%, transparent);
}
</style>

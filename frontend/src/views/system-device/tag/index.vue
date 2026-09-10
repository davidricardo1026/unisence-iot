<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white p-3">
    <el-form
        :model="params"
        inline
        size="small"
        class="compact-query-form mb-2 shrink-0"
    >
      <el-form-item
          label="标签键"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.tagKey"
            clearable
            class="!w-[120px]"
            placeholder="label"
        />
      </el-form-item>
      <el-form-item
          label="标签值"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.tagValue"
            clearable
            class="!w-[120px]"
        />
      </el-form-item>
      <div class="flex gap-1 !ml-auto">
        <el-button
            v-hasPermi="['iot:tag:list']"
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
          v-hasPermi="['iot:tag:add']"
          type="primary"
          size="small"
          @click="openDialog()"
      >
        <el-icon>
          <Plus/>
        </el-icon>
        新增
      </el-button>
      <span class="text-[11px] text-gray-400">共 {{ total }} 条标签</span>
    </div>

    <div class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="list"
          border
          stripe
          size="small"
          height="100%"
          style="width: 100%"
      >
        <el-table-column
            prop="tagKey"
            label="标签键"
            min-width="100"
        />
        <el-table-column
            prop="tagValue"
            label="标签值"
            min-width="120"
        />
        <el-table-column
            label="颜色"
            min-width="132"
        >
          <template #default="{ row }">
            <div
                v-if="normalizeColorValue(row.color)"
                class="compact-table-color"
            >
              <span
                  class="compact-table-color-swatch"
                  :style="{ background: getPreviewColor(row.color) }"
              />
              <span class="compact-table-color-value">
                {{ normalizeColorValue(row.color) }}
              </span>
            </div>
            <span
                v-else
                class="compact-table-color-empty"
            >
              未设置
            </span>
          </template>
        </el-table-column>
        <el-table-column
            prop="description"
            label="说明"
            min-width="160"
        />
        <el-table-column
            label="操作"
            width="90"
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
                  v-hasPermi="['iot:tag:edit']"
                  type="primary"
                  link
                  size="small"
                  class="!p-1"
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
                  v-hasPermi="['iot:tag:remove']"
                  type="danger"
                  link
                  size="small"
                  class="!p-1"
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
        :title="form.tagId ? '编辑标签' : '新增标签'"
        width="420px"
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
            label="标签键"
            prop="tagKey"
        >
          <el-input
              v-model="form.tagKey"
              maxlength="50"
              placeholder="如 label"
              :disabled="form.tagId !== undefined"
          />
        </el-form-item>
        <el-form-item
            label="标签值"
            prop="tagValue"
        >
          <el-input v-model="form.tagValue" maxlength="100"/>
        </el-form-item>
        <el-form-item label="颜色">
          <div class="compact-color-field">
            <div class="compact-color-picker-row">
              <input
                  :value="getNativeColorValue(form.color)"
                  type="color"
                  class="compact-color-picker"
                  @input="handleNativeColorInput"
              >
              <el-input
                  v-model="form.color"
                  maxlength="7"
                  placeholder="#1677ff"
              />
              <el-button
                  aria-label="随机生成颜色"
                  size="small"
                  text
                  @click="assignRandomColor"
              >
                <el-tooltip content="随机生成颜色" placement="top">
                  <el-icon>
                    <Refresh/>
                  </el-icon>
                </el-tooltip>
              </el-button>
              <el-button
                  aria-label="清空颜色"
                  size="small"
                  text
                  @click="clearColor"
              >
                <el-tooltip content="清空颜色" placement="top">
                  <el-icon>
                    <Delete/>
                  </el-icon>
                </el-tooltip>
              </el-button>
            </div>
            <div class="compact-color-preview-row">
              <span class="compact-color-preview-label">预览</span>
              <span
                  class="compact-color-preview"
                  :style="{ background: getPreviewColor(form.color) }"
              />
              <span class="compact-color-preview-value">
                {{ getPreviewLabel(form.color) }}
              </span>
            </div>
            <div class="compact-color-presets">
              <button
                  v-for="color in TAG_COLOR_PRESETS"
                  :key="color"
                  type="button"
                  class="compact-color-swatch"
                  :class="{'is-active': normalizeColorValue(form.color) === color}"
                  :style="{ background: color }"
                  :title="color"
                  @click="applyPresetColor(color)"
              />
            </div>
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" maxlength="255" show-word-limit/>
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
import {createTag, deleteTag, listTags, type TagQuery, type TagVO, updateTag} from '@/api/device'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'
import {getErrorMessage, isCancelError} from '@/utils/error'

const TAG_COLOR_PRESETS = [
  '#1677FF',
  '#13C2C2',
  '#52C41A',
  '#FAAD14',
  '#FA8C16',
  '#F5222D',
  '#EB2F96',
  '#722ED1',
  '#595959',
  '#8C8C8C'
] as const

const loading = ref(false)
const list = ref<TagVO[]>([])
const total = ref(0)
const params = reactive<PageRequest<TagQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {tagKey: '', tagValue: ''}
})

async function loadData() {
  loading.value = true
  try {
    const res = await listTags(params)
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  params.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadData()
}

function handleReset() {
  params.query!.tagKey = ''
  params.query!.tagValue = ''
  handleQuery()
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  tagId: undefined as number | undefined,
  tagKey: '',
  tagValue: '',
  color: '',
  description: '',
  version: 0
})
const rules: FormRules = {
  tagKey: [{required: true, message: '必填', trigger: 'blur'}],
  tagValue: [{required: true, message: '必填', trigger: 'blur'}]
}

function openDialog(row?: TagVO) {
  form.tagId = row?.tagId
  form.tagKey = row?.tagKey || ''
  form.tagValue = row?.tagValue || ''
  form.color = row?.color || (row ? '' : createRandomColor())
  form.description = row?.description || ''
  form.version = row?.version || 0
  dialogVisible.value = true
}

function createRandomColor() {
  const hue = Math.floor(Math.random() * 360)
  const saturation = 60 + Math.floor(Math.random() * 21)
  const lightness = 48 + Math.floor(Math.random() * 13)

  return hslToHex(hue, saturation, lightness)
}

function hslToHex(hue: number, saturation: number, lightness: number) {
  const normalizedSaturation = saturation / 100
  const normalizedLightness = lightness / 100
  const chroma = (1 - Math.abs(2 * normalizedLightness - 1)) * normalizedSaturation
  const hueSection = hue / 60
  const secondComponent = chroma * (1 - Math.abs((hueSection % 2) - 1))
  const match = normalizedLightness - chroma / 2

  let red = 0
  let green = 0
  let blue = 0

  if (hueSection >= 0 && hueSection < 1) {
    red = chroma
    green = secondComponent
  } else if (hueSection < 2) {
    red = secondComponent
    green = chroma
  } else if (hueSection < 3) {
    green = chroma
    blue = secondComponent
  } else if (hueSection < 4) {
    green = secondComponent
    blue = chroma
  } else if (hueSection < 5) {
    red = secondComponent
    blue = chroma
  } else {
    red = chroma
    blue = secondComponent
  }

  const toHex = (component: number) => Math.round((component + match) * 255).toString(16).padStart(2, '0')

  return `#${toHex(red)}${toHex(green)}${toHex(blue)}`.toUpperCase()
}

function normalizeColorValue(value?: string) {
  const normalized = value?.trim().toUpperCase() ?? ''
  return /^#[0-9A-F]{6}$/.test(normalized) ? normalized : ''
}

function getNativeColorValue(value?: string) {
  return normalizeColorValue(value) || '#1677FF'
}

function getPreviewColor(value?: string) {
  return normalizeColorValue(value) || '#F3F4F6'
}

function getPreviewLabel(value?: string) {
  return normalizeColorValue(value) || '未设置'
}

function applyPresetColor(color: string) {
  form.color = color
}

function clearColor() {
  form.color = ''
}

function assignRandomColor() {
  form.color = createRandomColor()
}

function handleNativeColorInput(event: Event) {
  const target = event.target as HTMLInputElement | null
  form.color = normalizeColorValue(target?.value) || ''
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  try {
    if (form.tagId) {
      await updateTag(form.tagId, {
        tagValue: form.tagValue,
        color: form.color || undefined,
        description: form.description || undefined,
        version: form.version
      })
      ElMessage.success('已更新')
    } else {
      const tagKey = form.tagKey.trim()
      form.tagKey = tagKey
      const duplicate = await listTags({
        pageNum: PAGE_DEFAULT.PAGE_NUM,
        pageSize: 1,
        query: {tagKey}
      })
      if ((duplicate.data?.total || 0) > 0) {
        ElMessage.error('标签键已存在，请使用其他标签键')
        return
      }
      await createTag({
        tagKey,
        tagValue: form.tagValue,
        color: form.color || undefined,
        description: form.description || undefined
      })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await loadData()
  } catch (error: unknown) {
    console.error('保存标签失败', error)
    ElMessage.error(getErrorMessage(error, '保存标签失败'))
  }
}

async function handleDelete(row: TagVO) {
  try {
    await ElMessageBox.confirm(`删除标签「${row.tagKey}:${row.tagValue}」？`, '提示', {type: 'warning'})
    await deleteTag(row.tagId)
    ElMessage.success('已删除')
    await loadData()
  } catch (error: unknown) {
    if (isCancelError(error)) {
      console.debug('用户取消删除标签', error)
      return
    }
    console.error('删除标签失败', error)
    ElMessage.error(getErrorMessage(error, '删除标签失败'))
  }
}

onMounted(loadData)
</script>

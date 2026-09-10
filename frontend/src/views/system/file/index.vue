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
          label="文件名称"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.fileName"
            placeholder="请输入"
            clearable
            class="!w-[140px]"
        />
      </el-form-item>
      <el-form-item
          label="存储目录"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.bucketName"
            placeholder="请输入"
            clearable
            class="!w-[120px]"
        />
      </el-form-item>
      <el-form-item
          label="上传时间"
          class="!mb-0"
      >
        <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :default-time="[new Date(0, 0, 0, 0, 0, 0), new Date(0, 0, 0, 23, 59, 59)]"
            size="small"
            class="!w-[240px]"
            @change="handleDateChange"
        />
      </el-form-item>
      <div class="flex gap-1">
        <el-button
            v-hasPermi="['sys:file:list']"
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
            v-hasPermi="['sys:file:list']"
            size="small"
            @click="handleReset"
        >
          <el-icon>
            <Refresh/>
          </el-icon>
          重置
        </el-button>
        <el-button
            v-hasPermi="['sys:file:upload']"
            type="success"
            size="small"
            @click="triggerUpload"
        >
          <el-icon>
            <Upload/>
          </el-icon>
          上传文件
        </el-button>
        <el-button
            v-hasPermi="['sys:file:delete']"
            type="danger"
            plain
            size="small"
            :disabled="selectedIds.length === 0"
            @click="handleBatchDelete"
        >
          <el-icon>
            <Delete/>
          </el-icon>
          批量删除
        </el-button>
      </div>
    </el-form>

    <!-- 隐藏上传 input -->
    <input
        ref="fileInputRef"
        type="file"
        class="hidden"
        multiple
        @change="handleFileChange"
    >

    <!-- 表格 -->
    <div class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="list"
          border
          stripe
          size="small"
          height="100%"
          @selection-change="handleSelectionChange"
      >
        <el-table-column
            type="selection"
            width="40"
            align="center"
        />
        <el-table-column
            prop="fileName"
            label="文件名称"
            min-width="200"
            show-overflow-tooltip
        />
        <el-table-column
            prop="fileSuffix"
            label="类型"
            width="70"
            align="center"
        >
          <template #default="{ row }">
            <el-tag
                size="small"
                type="info"
            >
              {{ row.fileSuffix || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
            prop="fileSize"
            label="大小"
            width="100"
            align="right"
        >
          <template #default="{ row }">
            <span class="text-xs text-gray-500">{{ formatSize(row.fileSize) }}</span>
          </template>
        </el-table-column>
        <el-table-column
            prop="bucketName"
            label="目录"
            width="100"
            show-overflow-tooltip
        />
        <el-table-column
            prop="createByName"
            label="上传人"
            width="100"
        />
        <el-table-column
            prop="createTime"
            label="上传时间"
            width="160"
        />
        <el-table-column
            prop="fileUrl"
            label="外链 URL"
            min-width="200"
            show-overflow-tooltip
        >
          <template #default="{ row }">
            <a
                :href="row.fileUrl"
                target="_blank"
                class="text-blue-500 hover:underline text-xs break-all"
            >
              {{ row.fileUrl }}
            </a>
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
                content="下载"
                placement="top"
                :enterable="false"
            >
              <el-button
                  v-hasPermi="['sys:file:download']"
                  type="primary"
                  link
                  size="small"
                  @click="handleDownload(row)"
              >
                <el-icon>
                  <Download/>
                </el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip
                content="删除"
                placement="top"
                :enterable="false"
            >
              <el-button
                  v-hasPermi="['sys:file:delete']"
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
  </div>
</template>

<script setup lang="ts">
import {ElMessage, ElMessageBox} from 'element-plus'
import {batchDeleteFiles, deleteFile, type FileInfoVO, type FileQuery, listFiles, uploadFile,} from '@/api/system'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'

const loading = ref(false)
const list = ref<FileInfoVO[]>([])
const total = ref(0)
const selectedIds = ref<number[]>([])
const fileInputRef = ref<HTMLInputElement>()
const dateRange = ref<[string, string] | null>(null)

const params = reactive<PageRequest<FileQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    fileName: '',
    bucketName: '',
    beginTime: undefined,
    endTime: undefined,
  },
})

async function loadData() {
  loading.value = true
  try {
    const res = await listFiles(params)
    list.value = res.data?.list || []
    total.value = res.data?.total || 0
  } catch (error) {
    console.error('加载文件列表失败', error)
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  params.pageNum = PAGE_DEFAULT.PAGE_NUM
  loadData()
}

function handleReset() {
  params.query!.fileName = ''
  params.query!.bucketName = ''
  params.query!.beginTime = undefined
  params.query!.endTime = undefined
  dateRange.value = null
  handleQuery()
}

function handleDateChange(val: [string, string] | null) {
  params.query!.beginTime = val?.[0]
  params.query!.endTime = val?.[1]
}

function handleSelectionChange(rows: FileInfoVO[]) {
  selectedIds.value = rows.map((r) => r.fileInfoId)
}

// ==================== 上传 ====================

function triggerUpload() {
  fileInputRef.value?.click()
}

async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const files = Array.from(input.files)
  input.value = ''

  let successCount = 0
  for (const file of files) {
    try {
      await uploadFile(file)
      successCount++
    } catch (err: unknown) {
      ElMessage.error(`${file.name} 上传失败：${getErrorMessage(err, '未知错误')}`)
    }
  }
  if (successCount > 0) {
    ElMessage.success(`成功上传 ${successCount} 个文件`)
    loadData()
  }
}

// ==================== 下载 ====================

function handleDownload(row: FileInfoVO) {
  const a = document.createElement('a')
  a.href = row.fileUrl
  a.download = row.fileName
  a.target = '_blank'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

// ==================== 删除 ====================

async function handleDelete(row: FileInfoVO) {
  try {
    await ElMessageBox.confirm(`确认删除文件"${row.fileName}"？此操作不可恢复。`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteFile(row.fileInfoId)
    ElMessage.success('删除成功')
    loadData()
  } catch (err: unknown) {
    if (!isCancelError(err)) {
      ElMessage.error(getErrorMessage(err))
    }
  }
}

async function handleBatchDelete() {
  if (selectedIds.value.length === 0) return
  try {
    await ElMessageBox.confirm(
        `确认批量删除已选中的 ${selectedIds.value.length} 个文件？此操作不可恢复。`,
        '确认批量删除',
        {type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'},
    )
    await batchDeleteFiles(selectedIds.value)
    ElMessage.success('批量删除成功')
    selectedIds.value = []
    loadData()
  } catch (err: unknown) {
    if (!isCancelError(err)) {
      ElMessage.error(getErrorMessage(err))
    }
  }
}

// ==================== 工具 ====================

function formatSize(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(1)} ${units[i]}`
}

onMounted(() => {
  loadData()
})
</script>

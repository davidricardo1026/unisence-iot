<template>
  <div class="flex-1 flex flex-col min-h-0 bg-white p-3">
    <!-- 搜索栏 -->
    <el-form
        :model="params"
        inline
        size="small"
        class="compact-query-form mb-2 shrink-0"
    >
      <el-form-item
          label="系统模块"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.title"
            placeholder="请输入"
            clearable
            class="!w-[120px]"
        />
      </el-form-item>
      <el-form-item
          label="操作人员"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.operatorCode"
            placeholder="请输入"
            clearable
            class="!w-[120px]"
        />
      </el-form-item>
      <el-form-item
          label="操作类型"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.businessType"
            placeholder="全部"
            clearable
            class="!w-[100px]"
        >
          <el-option
              label="其它"
              :value="0"
          />
          <el-option
              label="新增"
              :value="1"
          />
          <el-option
              label="修改"
              :value="2"
          />
          <el-option
              label="删除"
              :value="3"
          />
          <el-option
              label="授权"
              :value="4"
          />
          <el-option
              label="强退"
              :value="5"
          />
          <el-option
              label="清空"
              :value="6"
          />
          <el-option
              label="导出"
              :value="7"
          />
        </el-select>
      </el-form-item>
      <el-form-item
          label="操作状态"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.status"
            placeholder="全部"
            clearable
            class="!w-[90px]"
        >
          <el-option
              label="正常"
              :value="1"
          />
          <el-option
              label="异常"
              :value="0"
          />
        </el-select>
      </el-form-item>
      <el-form-item
          label="操作时间"
          class="!mb-0"
      >
        <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :default-time="[new Date(0,0,0,0,0,0), new Date(0,0,0,23,59,59)]"
            size="small"
            class="!w-[240px]"
            @change="handleDateChange"
        />
      </el-form-item>
      <div class="flex gap-1">
        <el-button
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

    <!-- 表格区域 -->
    <section class="flex min-h-0 flex-1 flex-col overflow-hidden">
      <div class="compact-action-toolbar">
        <div class="flex gap-2">
          <el-button
              v-hasPermi="['sys:operlog:remove']"
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
          <el-button
              v-hasPermi="['sys:operlog:remove']"
              type="danger"
              size="small"
              @click="handleClean"
          >
            <el-icon>
              <DeleteFilled/>
            </el-icon>
            清空
          </el-button>
        </div>
      </div>

      <div class="compact-table-region">
        <el-table
            border
            stripe
            size="small"
            :data="tableData"
            height="100%"
            @selection-change="handleSelectionChange"
        >
          <el-table-column
              type="selection"
              width="40"
          />
          <el-table-column
              prop="title"
              label="系统模块"
              width="100"
          />
          <el-table-column
              prop="businessType"
              label="操作类型"
              width="80"
              align="center"
          >
            <template #default="{row}">
              {{ BUSINESS_TYPE_MAP[row.businessType] ?? row.businessType }}
            </template>
          </el-table-column>
          <el-table-column
              prop="requestMethod"
              label="请求方式"
              width="70"
              align="center"
          />
          <el-table-column
              prop="operatorCode"
              label="操作人员"
              width="100"
          />
          <el-table-column
              prop="userName"
              label="用户名称"
              width="100"
          />
          <el-table-column
              prop="operIp"
              label="主机地址"
              width="120"
          />
          <el-table-column
              prop="operUrl"
              label="请求URL"
              min-width="160"
              show-overflow-tooltip
          />
          <el-table-column
              prop="status"
              label="操作状态"
              width="80"
              align="center"
          >
            <template #default="{row}">
              <el-tag
                  :type="row.status === 1 ? 'success' : 'danger'"
                  size="small"
              >
                {{ row.status === 1 ? '正常' : '异常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
              prop="createTime"
              label="操作时间"
              width="155"
          />
          <el-table-column
              label="操作"
              width="70"
              fixed="right"
              align="center"
          >
            <template #default="{row}">
              <el-tooltip
                  content="详情"
                  placement="top"
                  :enterable="false"
              >
                <el-button
                    v-hasPermi="['sys:operlog:query']"
                    type="primary"
                    link
                    @click="handleDetail(row)"
                >
                  <el-icon>
                    <View/>
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
            :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
            :total="total"
            layout="total, sizes, prev, pager, next"
            @change="fetchData"
        />
      </div>
    </section>

    <!-- 详情弹窗 -->
    <el-dialog
        v-model="detailVisible"
        title="操作日志详情"
        width="700px"
        destroy-on-close
    >
      <el-descriptions
          :column="2"
          border
          size="small"
      >
        <el-descriptions-item label="系统模块">
          {{ current.title }}
        </el-descriptions-item>
        <el-descriptions-item label="操作类型">
          {{ BUSINESS_TYPE_MAP[current.businessType!] }}
        </el-descriptions-item>
        <el-descriptions-item label="请求方式">
          {{ current.requestMethod }}
        </el-descriptions-item>
        <el-descriptions-item label="操作状态">
          <el-tag
              :type="current.status === 1 ? 'success' : 'danger'"
              size="small"
          >
            {{ current.status === 1 ? '正常' : '异常' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="操作人员">
          {{ current.operatorCode }}
        </el-descriptions-item>
        <el-descriptions-item label="用户名称">
          {{ current.userName }}
        </el-descriptions-item>
        <el-descriptions-item label="主机地址">
          {{ current.operIp }}
        </el-descriptions-item>
        <el-descriptions-item
            label="请求URL"
            :span="2"
        >
          {{ current.operUrl }}
        </el-descriptions-item>
        <el-descriptions-item
            label="方法名称"
            :span="2"
        >
          {{ current.method }}
        </el-descriptions-item>
        <el-descriptions-item
            label="操作时间"
            :span="2"
        >
          {{ current.createTime }}
        </el-descriptions-item>
        <el-descriptions-item
            v-if="current.errorMsg"
            label="异常信息"
            :span="2"
        >
          <span class="text-red-500">{{ current.errorMsg }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <div
          v-if="current.operParam"
          class="mt-3"
      >
        <div class="text-xs text-gray-500 mb-1">
          请求参数
        </div>
        <pre class="bg-gray-50 rounded p-2 text-xs overflow-auto max-h-40">{{ formatJson(current.operParam) }}</pre>
      </div>
      <div
          v-if="current.jsonResult"
          class="mt-3"
      >
        <div class="text-xs text-gray-500 mb-1">
          返回结果
        </div>
        <pre class="bg-gray-50 rounded p-2 text-xs overflow-auto max-h-40">{{ formatJson(current.jsonResult) }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {batchDeleteOperLogs, cleanOperLogs, listOperLogs, type OperLogQuery, type OperLogVO,} from '@/api/system'
import {ElMessage, ElMessageBox} from 'element-plus'
import {PAGE_DEFAULT} from '@/types/api'

const BUSINESS_TYPE_MAP: Record<number, string> = {
  0: '其它', 1: '新增', 2: '修改', 3: '删除',
  4: '授权', 5: '强退', 6: '清空', 7: '导出',
}

const params = reactive<{ pageNum: number; pageSize: number; query: OperLogQuery }>({
  pageNum: PAGE_DEFAULT.PAGE_NUM, pageSize: PAGE_DEFAULT.PAGE_SIZE, query: {},
})
const dateRange = ref<[string, string] | null>(null)
const tableData = ref<OperLogVO[]>([])
const total = ref(0)
const selectedIds = ref<number[]>([])
const detailVisible = ref(false)
const current = ref<Partial<OperLogVO>>({})

function handleDateChange(val: [string, string] | null) {
  params.query.beginTime = val?.[0] ?? undefined
  params.query.endTime = val?.[1] ?? undefined
}

async function fetchData() {
  const res = await listOperLogs(params)
  tableData.value = res.data?.list ?? []
  total.value = res.data?.total ?? 0
}

function handleQuery() {
  params.pageNum = 1;
  fetchData()
}

function handleReset() {
  params.query = {};
  dateRange.value = null;
  params.pageNum = 1;
  fetchData()
}

function handleSelectionChange(rows: OperLogVO[]) {
  selectedIds.value = rows.map(r => r.operLogId)
}

function handleDetail(row: OperLogVO) {
  current.value = row
  detailVisible.value = true
}

async function handleBatchDelete() {
  await ElMessageBox.confirm(`确认删除选中的 ${selectedIds.value.length} 条记录？`, '提示', {type: 'warning'})
  await batchDeleteOperLogs(selectedIds.value)
  ElMessage.success('删除成功')
  fetchData()
}

async function handleClean() {
  await ElMessageBox.confirm('确认清空所有操作日志？此操作不可恢复！', '警告', {type: 'warning'})
  await cleanOperLogs()
  ElMessage.success('已清空')
  fetchData()
}

function formatJson(str: string): string {
  try {
    return JSON.stringify(JSON.parse(str), null, 2)
  } catch (error) {
    console.debug('操作日志 JSON 不是有效对象，按原文展示', error)
    return str
  }
}

onMounted(fetchData)
</script>

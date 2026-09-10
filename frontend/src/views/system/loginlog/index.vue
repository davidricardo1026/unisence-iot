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
          label="登录账号"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.userCode"
            placeholder="请输入"
            clearable
            class="!w-[130px]"
        />
      </el-form-item>
      <el-form-item
          label="登录IP"
          class="!mb-0"
      >
        <el-input
            v-model="params.query!.ipaddr"
            placeholder="请输入"
            clearable
            class="!w-[130px]"
        />
      </el-form-item>
      <el-form-item
          label="登录状态"
          class="!mb-0"
      >
        <el-select
            v-model="params.query!.status"
            placeholder="全部"
            clearable
            class="!w-[100px]"
        >
          <el-option
              label="成功"
              :value="1"
          />
          <el-option
              label="失败"
              :value="0"
          />
        </el-select>
      </el-form-item>
      <el-form-item
          label="登录时间"
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
              v-hasPermi="['sys:loginlog:remove']"
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
              v-hasPermi="['sys:loginlog:clean']"
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
              prop="userCode"
              label="登录账号"
              width="120"
          />
          <el-table-column
              prop="userName"
              label="用户名称"
              width="100"
          />
          <el-table-column
              prop="ipaddr"
              label="IP地址"
              width="130"
          />
          <el-table-column
              prop="loginLocation"
              label="登录地点"
              width="100"
          />
          <el-table-column
              prop="browser"
              label="浏览器"
              width="160"
              show-overflow-tooltip
          />
          <el-table-column
              prop="os"
              label="操作系统"
              width="100"
          />
          <el-table-column
              prop="status"
              label="登录状态"
              width="90"
              align="center"
          >
            <template #default="{row}">
              <el-tag
                  :type="row.status === 1 ? 'success' : 'danger'"
                  size="small"
              >
                {{ row.status === 1 ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
              prop="msg"
              label="提示消息"
              min-width="150"
              show-overflow-tooltip
          />
          <el-table-column
              prop="loginTime"
              label="登录时间"
              width="155"
          />
          <el-table-column
              label="操作"
              width="80"
              fixed="right"
              align="center"
          >
            <template #default="{row}">
              <el-tooltip
                  content="删除"
                  placement="top"
                  :enterable="false"
              >
                <el-button
                    v-hasPermi="['sys:loginlog:remove']"
                    type="danger"
                    link
                    @click="handleDelete(row.loginLogId)"
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
            :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
            :total="total"
            layout="total, sizes, prev, pager, next"
            @change="fetchData"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import {
  batchDeleteLoginLogs,
  cleanLoginLogs,
  deleteLoginLog,
  listLoginLogs,
  type LoginLogQuery,
  type LoginLogVO,
} from '@/api/system'
import {ElMessage, ElMessageBox} from 'element-plus'
import {PAGE_DEFAULT} from '@/types/api'

const params = reactive<{ pageNum: number; pageSize: number; query: LoginLogQuery }>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {},
})
const dateRange = ref<[string, string] | null>(null)
const tableData = ref<LoginLogVO[]>([])
const total = ref(0)
const selectedIds = ref<number[]>([])

function handleDateChange(val: [string, string] | null) {
  params.query.beginTime = val?.[0] ?? undefined
  params.query.endTime = val?.[1] ?? undefined
}

async function fetchData() {
  const res = await listLoginLogs(params)
  tableData.value = res.data?.list ?? []
  total.value = res.data?.total ?? 0
}

function handleQuery() {
  params.pageNum = 1
  fetchData()
}

function handleReset() {
  params.query = {}
  dateRange.value = null
  params.pageNum = 1
  fetchData()
}

function handleSelectionChange(rows: LoginLogVO[]) {
  selectedIds.value = rows.map(r => r.loginLogId)
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确认删除该条登录记录？', '提示', {type: 'warning'})
  await deleteLoginLog(id)
  ElMessage.success('删除成功')
  fetchData()
}

async function handleBatchDelete() {
  await ElMessageBox.confirm(`确认删除选中的 ${selectedIds.value.length} 条记录？`, '提示', {type: 'warning'})
  await batchDeleteLoginLogs(selectedIds.value)
  ElMessage.success('删除成功')
  fetchData()
}

async function handleClean() {
  await ElMessageBox.confirm('确认清空所有登录日志？此操作不可恢复！', '警告', {type: 'warning'})
  await cleanLoginLogs()
  ElMessage.success('已清空')
  fetchData()
}

onMounted(fetchData)
</script>
